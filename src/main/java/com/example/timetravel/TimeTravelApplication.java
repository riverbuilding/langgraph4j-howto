package com.example.timetravel;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiChatModel;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.bsc.async.AsyncGenerator;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.action.EdgeAction;
import org.bsc.langgraph4j.action.NodeAction;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.langchain4j.serializer.std.LC4jStateSerializer;
import org.bsc.langgraph4j.langchain4j.tool.LC4jToolService;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.state.StateSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static dev.langchain4j.model.openai.OpenAiChatModelName.GPT_4_O_MINI;
import static java.time.Duration.ofSeconds;
import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

public class TimeTravelApplication {

    private static final Logger log = LoggerFactory.getLogger(TimeTravelApplication.class);

    public static void main(String[] args) throws Exception {
        ChatModel model = createModel();
        StateGraph<State> workflow = createWorkflow(model);

        log.info("Running the graph with checkpoint memory");
        CompileConfig memoryConfig = CompileConfig.builder()
                .checkpointSaver(new MemorySaver())
                .releaseThread(false)
                .build();
        CompiledGraph<State> graph = workflow.compile(memoryConfig);

        RunnableConfig conversationOne = RunnableConfig.builder()
                .threadId("conversation-num-1")
                .build();
        stream(graph, conversationOne, "Hi I'm Bartolo.");
        StateSnapshot<State> checkpoint = graph.getState(conversationOne);
        log.info("Latest state next node: {}", checkpoint.next());

        State weatherState = graph.invoke(
                        Map.of("messages", UserMessage.from("What's the weather like in SF currently?")),
                        conversationOne)
                .orElseThrow(() -> new IllegalStateException("No state returned from weather run"));
        log.info("Final weather answer: {}", messageText(weatherState.lastMessage().orElse(null)));

        log.info("Running with interruptBefore(\"tools\")");
        CompileConfig interruptConfig = CompileConfig.builder()
                .checkpointSaver(new MemorySaver())
                .releaseThread(false)
                .interruptBefore("tools")
                .build();
        CompiledGraph<State> graphWithInterrupt = workflow.compile(interruptConfig);
        RunnableConfig conversationTwo = RunnableConfig.builder()
                .threadId("conversation-2")
                .build();

        stream(graphWithInterrupt, conversationTwo, "What's the weather like in SF currently?");

        StateSnapshot<State> snapshot = graphWithInterrupt.getState(conversationTwo);
        log.info("Interrupted before node: {}", snapshot.next());

        log.info("Resuming from the interrupted checkpoint");
        stream(graphWithInterrupt, snapshot.config());

        log.info("Checking full checkpoint history and selecting a prior state to replay");
        RunnableConfig replayConfig = findReplayConfig(graphWithInterrupt, conversationTwo);

        log.info("Replaying from checkpoint: {}", replayConfig.checkPointId().orElse("unknown"));
        stream(graphWithInterrupt, replayConfig);
    }

    private static ChatModel createModel() {
        String baseUrl = getenvOrDefault("TIME_TRAVEL_OPENAI_BASE_URL", "http://langchain4j.dev/demo/openai/v1");
        String apiKey = getenvOrDefault("TIME_TRAVEL_OPENAI_API_KEY", "demo");
        String modelName = getenvOrDefault("TIME_TRAVEL_MODEL", GPT_4_O_MINI.toString());

        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .maxRetries(2)
                .temperature(0.0)
                .maxTokens(2000)
                .timeout(ofSeconds(60))
                .build();
    }

    private static StateGraph<State> createWorkflow(ChatModel model) throws GraphStateException {
        LC4jToolService toolService = LC4jToolService.builder()
                .toolsFromObject(new SearchTool())
                .build();

        EdgeAction<State> routeMessage = state -> {
            ChatMessage lastMessage = state.lastMessage().orElse(null);
            if (!(lastMessage instanceof AiMessage message)) {
                return "exit";
            }
            return message.hasToolExecutionRequests() ? "next" : "exit";
        };

        NodeAction<State> callModel = state -> {
            ChatRequestParameters parameters = ChatRequestParameters.builder()
                    .toolSpecifications(toolService.toolSpecifications())
                    .build();
            ChatRequest request = ChatRequest.builder()
                    .parameters(parameters)
                    .messages(state.messages())
                    .build();

            return Map.of("messages", model.chat(request).aiMessage());
        };

        AsyncNodeAction<State> invokeTool = state -> {
            ChatMessage lastMessage = state.lastMessage().orElse(null);
            if (!(lastMessage instanceof AiMessage aiMessage)) {
                return CompletableFuture.failedFuture(new IllegalStateException("last message is not an AiMessage"));
            }

            return toolService.execute(
                            aiMessage.toolExecutionRequests(),
                            InvocationContext.builder().build(),
                            "messages")
                    .thenApply(command -> command.update());
        };

        LC4jStateSerializer<State> stateSerializer = new LC4jStateSerializer<>(State::new);

        return new StateGraph<>(State.SCHEMA, stateSerializer)
                .addNode("agent", node_async(callModel))
                .addNode("tools", invokeTool)
                .addEdge(START, "agent")
                .addConditionalEdges("agent", edge_async(routeMessage), Map.of(
                        "next", "tools",
                        "exit", END))
                .addEdge("tools", "agent");
    }

    private static void stream(
            CompiledGraph<State> graph,
            RunnableConfig config,
            String userText) {

        stream(graph, GraphInput.args(Map.of("messages", UserMessage.from(userText))), config);
    }

    private static void stream(CompiledGraph<State> graph, RunnableConfig config) {
        stream(graph, GraphInput.resume(), config);
    }

    private static void stream(
            CompiledGraph<State> graph,
            GraphInput input,
            RunnableConfig config) {

        AsyncGenerator.Cancellable<NodeOutput<State>> result = graph.stream(input, config);
        for (NodeOutput<State> output : result) {
            log.info("{} -> {}", output.node(), messageText(output.state().lastMessage().orElse(null)));
        }
    }

    private static RunnableConfig findReplayConfig(
            CompiledGraph<State> graph,
            RunnableConfig config) {

        RunnableConfig toReplay = null;
        for (StateSnapshot<State> state : graph.getStateHistory(config)) {
            log.info("History node={}, next={}, messages={}",
                    state.node(),
                    state.next(),
                    state.state().messages().size());

            if (state.state().messages().size() == 3) {
                toReplay = state.config();
            }
        }

        if (toReplay == null) {
            throw new IllegalStateException("No state to replay");
        }
        return toReplay;
    }

    private static String messageText(ChatMessage message) {
        if (message == null) {
            return "";
        }
        return switch (message.type()) {
            case USER -> ((UserMessage) message).singleText();
            case AI -> ((AiMessage) message).hasToolExecutionRequests()
                    ? ((AiMessage) message).toolExecutionRequests().toString()
                    : ((AiMessage) message).text();
            case TOOL_EXECUTION_RESULT -> ((ToolExecutionResultMessage) message).text();
            default -> message.toString();
        };
    }

    private static String getenvOrDefault(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    public static class State extends MessagesState<ChatMessage> {

        public State(Map<String, Object> initData) {
            super(initData);
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
