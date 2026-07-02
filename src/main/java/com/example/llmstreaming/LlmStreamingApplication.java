package com.example.llmstreaming;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.action.EdgeAction;
import org.bsc.langgraph4j.action.NodeAction;
import org.bsc.langgraph4j.langchain4j.generators.StreamingChatGenerator;
import org.bsc.langgraph4j.langchain4j.serializer.std.LC4jStateSerializer;
import org.bsc.langgraph4j.langchain4j.tool.LC4jToolService;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.prebuilt.MessagesStateGraph;
import org.bsc.langgraph4j.streaming.StreamingOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

public class LlmStreamingApplication {

    private static final Logger log = LoggerFactory.getLogger(LlmStreamingApplication.class);

    public static void main(String[] args) throws GraphStateException {
        StreamingChatModel model = createModel();

        log.info("Running direct streaming model call");
        runDirectStreamingCall(model);

        log.info("Running LangGraph4j workflow with streaming model output");
        CompiledGraph<MessagesState<ChatMessage>> app = createWorkflow(model).compile();
        streamGraph(app, getenvOrDefault("LLM_STREAMING_QUESTION", "what is the weather in Napoli?"));
    }

    private static StreamingChatModel createModel() {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(getenvOrDefault("LLM_STREAMING_OPENAI_BASE_URL", "http://langchain4j.dev/demo/openai/v1"))
                .apiKey(getenvOrDefault("LLM_STREAMING_OPENAI_API_KEY", getenvOrDefault("OPENAI_API_KEY", "demo")))
                .modelName(getenvOrDefault("LLM_STREAMING_MODEL", "gpt-4o-mini"))
                .temperature(0.0)
                .logRequests(Boolean.parseBoolean(getenvOrDefault("LLM_STREAMING_LOG_REQUESTS", "true")))
                .logResponses(Boolean.parseBoolean(getenvOrDefault("LLM_STREAMING_LOG_RESPONSES", "true")))
                .timeout(Duration.ofSeconds(Long.parseLong(getenvOrDefault("LLM_STREAMING_TIMEOUT_SECONDS", "60"))))
                .build();
    }

    private static void runDirectStreamingCall(StreamingChatModel model) {
        StreamingChatGenerator<MessagesState<String>> generator = StreamingChatGenerator.<MessagesState<String>>builder()
                .mapResult(response -> Map.of("content", response.aiMessage().text()))
                .build();

        ChatRequest request = ChatRequest.builder()
                .messages(UserMessage.from(getenvOrDefault("LLM_STREAMING_DIRECT_PROMPT", "Tell me a joke")))
                .build();

        model.chat(request, generator.handler());

        for (StreamingOutput<MessagesState<String>> output : generator) {
            log.info("direct chunk: {}", output.chunk());
        }

        log.info("direct result: {}", generator.resultValue().orElse(null));
    }

    private static StateGraph<MessagesState<ChatMessage>> createWorkflow(StreamingChatModel model) throws GraphStateException {
        LC4jToolService tools = LC4jToolService.builder()
                .toolsFromObject(new SearchTool())
                .build();

        NodeAction<MessagesState<ChatMessage>> callModel = state -> {
            log.info("CallModel");

            StreamingChatGenerator<MessagesState<ChatMessage>> generator =
                    StreamingChatGenerator.<MessagesState<ChatMessage>>builder()
                            .mapResult(response -> Map.of("messages", response.aiMessage()))
                            .startingNode("agent")
                            .startingState(state)
                            .build();

            ChatRequestParameters parameters = ChatRequestParameters.builder()
                    .toolSpecifications(tools.toolSpecifications())
                    .build();

            ChatRequest request = ChatRequest.builder()
                    .parameters(parameters)
                    .messages(state.messages())
                    .build();

            model.chat(request, generator.handler());

            return Map.of("_streaming_messages", generator);
        };

        EdgeAction<MessagesState<ChatMessage>> routeMessage = state -> {
            ChatMessage lastMessage = state.lastMessage()
                    .orElseThrow(() -> new IllegalStateException("last message not found"));

            log.info("routeMessage:\n{}", lastMessage);

            if (lastMessage instanceof AiMessage aiMessage && aiMessage.hasToolExecutionRequests()) {
                return "next";
            }

            return "exit";
        };

        AsyncNodeAction<MessagesState<ChatMessage>> invokeTool = state -> {
            ChatMessage lastMessage = state.lastMessage()
                    .orElse(null);

            log.info("invokeTool:\n{}", lastMessage);

            if (lastMessage instanceof AiMessage aiMessage) {
                return tools.execute(
                                aiMessage.toolExecutionRequests(),
                                InvocationContext.builder().build(),
                                "messages")
                        .thenApply(command -> command.update());
            }

            return CompletableFuture.failedFuture(new IllegalStateException("invalid last message"));
        };

        LC4jStateSerializer<MessagesState<ChatMessage>> stateSerializer =
                new LC4jStateSerializer<>(MessagesState::new);

        return new MessagesStateGraph<ChatMessage>(stateSerializer)
                .addNode("agent", node_async(callModel))
                .addNode("tools", invokeTool)
                .addEdge(START, "agent")
                .addConditionalEdges("agent", edge_async(routeMessage), Map.of(
                        "next", "tools",
                        "exit", END))
                .addEdge("tools", "agent");
    }

    private static void streamGraph(CompiledGraph<MessagesState<ChatMessage>> app, String question) {
        for (NodeOutput<MessagesState<ChatMessage>> output : app.stream(Map.of("messages", UserMessage.from(question)))) {
            if (output instanceof StreamingOutput<MessagesState<ChatMessage>> streaming) {
                log.info("StreamingOutput{node={}, chunk={}}", streaming.node(), streaming.chunk());
            } else {
                log.info("{}", output);
            }
        }
    }

    private static String getenvOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    public static class SearchTool {

        @Tool("get weather realtime information.")
        String execQuery(@P("city") String city) {
            log.info("SearchTool city: {}", city);
            return "Cold, with a low of 13 degrees";
        }
    }
}
