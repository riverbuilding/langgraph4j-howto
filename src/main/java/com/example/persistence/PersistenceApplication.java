package com.example.persistence;

import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import java.util.List;
import java.util.Map;
import org.bsc.async.AsyncGenerator;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.EdgeAction;
import org.bsc.langgraph4j.action.NodeAction;
import org.bsc.langgraph4j.checkpoint.HazelcastSaver;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.langchain4j.serializer.std.ChatMesssageSerializer;
import org.bsc.langgraph4j.langchain4j.serializer.std.ToolExecutionRequestSerializer;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static dev.langchain4j.model.openai.OpenAiChatModelName.GPT_4_O_MINI;
import static java.time.Duration.ofSeconds;
import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

public class PersistenceApplication {

    private static final Logger log = LoggerFactory.getLogger(PersistenceApplication.class);

    public static void main(String[] args) throws Exception {
        ChatModel model = OpenAiChatModel.builder()
                .baseUrl("http://langchain4j.dev/demo/openai/v1")
                .apiKey("demo")
                .modelName(GPT_4_O_MINI)
                .maxRetries(2)
                .temperature(0.0)
                .maxTokens(2000)
                .timeout(ofSeconds(60))
                .build();

        StateSerializer stateSerializer = new StateSerializer();
        StateGraph<MessageState> workflow = createWorkflow(model, stateSerializer);

        log.info("Running without checkpoint memory");
        CompiledGraph<MessageState> graph = workflow.compile();
        runConversationTurn(graph, RunnableConfig.empty(), "Hi, I'm Bartolo. Nice to meet you.");
        runConversationTurn(graph, RunnableConfig.empty(), "Remember my name?");

        log.info("Running with MemorySaver on the same thread");
        CompileConfig memoryConfig = CompileConfig.builder()
                .checkpointSaver(new MemorySaver())
                .build();
        CompiledGraph<MessageState> memoryGraph = workflow.compile(memoryConfig);
        RunnableConfig conversationOne = RunnableConfig.builder()
                .threadId("conversation-num-1")
                .build();
        runConversationTurn(memoryGraph, conversationOne, "Hi, I'm Bartolo. Nice to meet you.");
        runConversationTurn(memoryGraph, conversationOne, "Remember my name?");

        log.info("Running with MemorySaver on a new thread");
        RunnableConfig conversationTwo = RunnableConfig.builder()
                .threadId("conversation-num-2")
                .build();
        runConversationTurn(memoryGraph, conversationTwo, "Do you know my name?");

        log.info("Running with HazelcastSaver");
        runHazelcastConversation(workflow, stateSerializer);
    }

    private static StateGraph<MessageState> createWorkflow(
            ChatModel model,
            StateSerializer stateSerializer) throws GraphStateException {

        SearchTool searchTool = new SearchTool();
        List<ToolSpecification> toolSpecifications = ToolSpecifications.toolSpecificationsFrom(searchTool);

        EdgeAction<MessageState> routeMessage = state -> {
            ChatMessage lastMessage = state.lastMessage().orElse(null);
            if (!(lastMessage instanceof AiMessage aiMessage)) {
                return "exit";
            }
            return aiMessage.hasToolExecutionRequests() ? "next" : "exit";
        };

        NodeAction<MessageState> callModel = state -> {
            ChatRequest request = ChatRequest.builder()
                    .messages(state.messages())
                    .toolSpecifications(toolSpecifications)
                    .build();

            return Map.of("messages", model.chat(request).aiMessage());
        };

        NodeAction<MessageState> invokeTool = state -> {
            AiMessage lastMessage = (AiMessage) state.lastMessage()
                    .orElseThrow(() -> new IllegalStateException("last message not found"));
            ToolExecutionRequest executionRequest = lastMessage.toolExecutionRequests().get(0);
            String result = new DefaultToolExecutor(searchTool, executionRequest)
                    .execute(executionRequest, null);

            return Map.of("messages", ToolExecutionResultMessage.from(executionRequest, result));
        };

        return new StateGraph<>(MessageState.SCHEMA, stateSerializer)
                .addNode("agent", node_async(callModel))
                .addNode("tools", node_async(invokeTool))
                .addEdge(START, "agent")
                .addConditionalEdges("agent", edge_async(routeMessage), Map.of(
                        "next", "tools",
                        "exit", END))
                .addEdge("tools", "agent");
    }

    private static void runConversationTurn(
            CompiledGraph<MessageState> graph,
            RunnableConfig config,
            String userText) {

        log.info("USER: {}", userText);
        Map<String, Object> input = Map.of("messages", UserMessage.from(userText));
        AsyncGenerator<NodeOutput<MessageState>> result = graph.stream(input, config);

        for (NodeOutput<MessageState> output : result) {
            if ("agent".equals(output.node())) {
                log.info("ASSISTANT: {}", messageText(output.state().lastMessage().orElse(null)));
            }
        }
    }

    private static void runHazelcastConversation(
            StateGraph<MessageState> workflow,
            StateSerializer stateSerializer) throws GraphStateException {

        Config hzConfig = new Config();
        hzConfig.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        hzConfig.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(false);

        HazelcastInstance hazelcast = Hazelcast.newHazelcastInstance(hzConfig);
        try {
            HazelcastSaver hazelcastSaver = HazelcastSaver.builder()
                    .hazelcastInstance(hazelcast)
                    .stateSerializer(stateSerializer)
                    .build();

            CompileConfig compileConfig = CompileConfig.builder()
                    .checkpointSaver(hazelcastSaver)
                    .build();

            CompiledGraph<MessageState> graph = workflow.compile(compileConfig);
            RunnableConfig config = RunnableConfig.builder()
                    .threadId("hazelcast-conversation-1")
                    .build();

            runConversationTurn(graph, config, "Hi, I'm Bartolo. Nice to meet you.");
            runConversationTurn(graph, config, "Remember my name?");
        } finally {
            hazelcast.shutdown();
        }
    }

    private static String messageText(ChatMessage message) {
        if (message == null) {
            return "";
        }
        return switch (message.type()) {
            case USER -> ((UserMessage) message).singleText();
            case AI -> ((AiMessage) message).text();
            case TOOL_EXECUTION_RESULT -> ((ToolExecutionResultMessage) message).text();
            default -> message.toString();
        };
    }

    public static class MessageState extends MessagesState<ChatMessage> {

        public MessageState(Map<String, Object> initData) {
            super(initData);
        }
    }

    public static class StateSerializer extends ObjectStreamStateSerializer<MessageState> {

        public StateSerializer() {
            super(MessageState::new);
            mapper().register(ToolExecutionRequest.class, new ToolExecutionRequestSerializer());
            mapper().register(ChatMessage.class, new ChatMesssageSerializer());
        }
    }

    public static class SearchTool {

        @Tool("Use to surf the web, fetch current information, check the weather, and retrieve other information.")
        String execQuery(@P("The query to use in your search.") String query) {
            log.info("SearchTool query: {}", query);
            return "Cold, with a low of 13 degrees";
        }
    }
}
