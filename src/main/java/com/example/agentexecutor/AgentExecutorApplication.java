package com.example.agentexecutor;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.openai.OpenAiChatModel;
import java.util.Map;
import java.util.Optional;
import org.bsc.async.AsyncGenerator;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.agentexecutor.AgentExecutor;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.lang.String.format;

public class AgentExecutorApplication {

    private static final Logger log = LoggerFactory.getLogger(AgentExecutorApplication.class);

    public static void main(String[] args) throws GraphStateException {
        String apiKey = System.getenv("OPENAI_API_KEY");
//        if (apiKey == null || apiKey.isBlank()) {
//            throw new IllegalStateException("OPENAI_API_KEY is required to run the Agent Executor example.");
//        }

        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .baseUrl("http://langchain4j.dev/demo/openai/v1")
                .apiKey("demo")
                .modelName("gpt-4o-mini")
                //.logResponses(true)
                .maxRetries(2)
                .temperature(0.0)
                .maxTokens(2000)
                .build();

        StateGraph<AgentExecutor.State> stateGraph = AgentExecutor.builder()
                .chatModel(chatModel)
                .toolsFromObject(new TestTool())
                .build();

        MemorySaver saver = new MemorySaver();
        CompileConfig compileConfig = CompileConfig.builder()
                .checkpointSaver(saver)
                .build();

        CompiledGraph<AgentExecutor.State> graph = stateGraph.compile(compileConfig);

        runThread(graph, "test1", "perform test once");
        //runThread(graph, "test2", "perform test once");
    }

    private static void runThread(
            CompiledGraph<AgentExecutor.State> graph,
            String threadId,
            String message) throws GraphStateException {

        RunnableConfig config = RunnableConfig.builder()
                .threadId(threadId)
                .build();

        Map<String, Object> input = Map.of("messages", UserMessage.from(message));
        AsyncGenerator.Cancellable<NodeOutput<AgentExecutor.State>> iterator = graph.streamSnapshots(input, config);

        for (NodeOutput<AgentExecutor.State> step : iterator) {
            log.info("STEP: {}", step);
        }

//        List<?> history = graph.getStateHistory(config).stream().toList();
//
//        for (var snapshot : history) {
//            log.info("{}", snapshot);
//        }
    }

    public static class TestTool {

        private String lastResult;

        Optional<String> lastResult() {
            return Optional.ofNullable(lastResult);
        }

        @Tool("tool for test AI agent executor")
        String execTest(@P("test message") String message) {
            lastResult = format("test tool executed: %s", message);
            return lastResult;
        }
    }
}
