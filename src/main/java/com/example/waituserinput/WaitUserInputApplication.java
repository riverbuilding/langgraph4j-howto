package com.example.waituserinput;

import java.util.Map;
import java.util.Optional;
import org.bsc.async.AsyncGenerator;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.checkpoint.PostgresSaver;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

public class WaitUserInputApplication {

    private static final Logger log = LoggerFactory.getLogger(WaitUserInputApplication.class);

    public static void main(String[] args) throws Exception {
        StateGraph<State> workflow = createWorkflow();
        CompiledGraph<State> graph = workflow.compile(createCompileConfig(workflow));

        RunnableConfig invokeConfig = RunnableConfig.builder()
                .threadId("wait-user-input-thread-1")
                .build();

        log.info("Starting graph until interruptBefore(\"human_feedback\")");
        stream(graph, GraphInput.args(Map.of("messages", "Step 0")), invokeConfig);

        RunnableConfig backConfig = applyUserFeedback(graph, invokeConfig, "back");

        log.info("Resuming after first feedback");
        stream(graph, GraphInput.resume(), backConfig);

        RunnableConfig nextConfig = applyUserFeedback(graph, invokeConfig, "next");

        log.info("Resuming after second feedback");
        stream(graph, GraphInput.resume(), nextConfig);
    }

    private static StateGraph<State> createWorkflow() throws GraphStateException {
        AsyncNodeAction<State> step1 = node_async(state -> Map.of("messages", "Step 1"));
        AsyncNodeAction<State> humanFeedback = node_async(state -> Map.of());
        AsyncNodeAction<State> step3 = node_async(state -> Map.of("messages", "Step 3"));

        AsyncEdgeAction<State> evalHumanFeedback = edge_async(state -> {
            String feedback = state.humanFeedback()
                    .orElseThrow(() -> new IllegalStateException("human_feedback is missing"));
            return feedback.equals("next") || feedback.equals("back") ? feedback : "unknown";
        });

        return new StateGraph<>(State.SCHEMA, State::new)
                .addNode("step_1", step1)
                .addNode("human_feedback", humanFeedback)
                .addNode("step_3", step3)
                .addEdge(START, "step_1")
                .addEdge("step_1", "human_feedback")
                .addConditionalEdges("human_feedback", evalHumanFeedback, Map.of(
                        "back", "step_1",
                        "next", "step_3",
                        "unknown", "human_feedback"))
                .addEdge("step_3", END);
    }

    private static CompileConfig createCompileConfig(StateGraph<State> workflow) throws Exception {
        CompileConfig.Builder builder = CompileConfig.builder()
                .interruptBefore("human_feedback")
                .releaseThread(true);

        if (Boolean.parseBoolean(getenvOrDefault("WAIT_USER_INPUT_USE_POSTGRES", "false"))) {
            return builder.checkpointSaver(PostgresSaver.builder()
                            .host(getenvOrDefault("WAIT_USER_INPUT_POSTGRES_HOST", "localhost"))
                            .port(Integer.parseInt(getenvOrDefault("WAIT_USER_INPUT_POSTGRES_PORT", "5432")))
                            .user(getenvOrDefault("WAIT_USER_INPUT_POSTGRES_USER", "admin"))
                            .password(getenvOrDefault("WAIT_USER_INPUT_POSTGRES_PASSWORD", "bsorrentino"))
                            .database(getenvOrDefault("WAIT_USER_INPUT_POSTGRES_DATABASE", "lg4j-store"))
                            .stateSerializer(workflow.getStateSerializer())
                            .dropTablesFirst(Boolean.parseBoolean(
                                    getenvOrDefault("WAIT_USER_INPUT_POSTGRES_DROP_TABLES_FIRST", "true")))
                            .build())
                    .build();
        }

        return builder.checkpointSaver(new MemorySaver())
                .build();
    }

    private static RunnableConfig applyUserFeedback(
            CompiledGraph<State> graph,
            RunnableConfig invokeConfig,
            String userInput) throws Exception {

        log.info("State before update: {}", graph.getState(invokeConfig));
        log.info("Simulated user feedback: {}", userInput);

        RunnableConfig updateConfig = graph.updateState(
                invokeConfig,
                Map.of("human_feedback", userInput),
                null);

        log.info("State after update: {}", graph.getState(invokeConfig));
        log.info("Next node from invoke config: {}", graph.getState(invokeConfig).next());
        log.info("Next node from update config: {}", graph.getState(updateConfig).next());

        return updateConfig;
    }

    private static void stream(
            CompiledGraph<State> graph,
            GraphInput input,
            RunnableConfig config) {

        AsyncGenerator.Cancellable<NodeOutput<State>> result = graph.stream(input, config);
        for (NodeOutput<State> output : result) {
            log.info("{} -> messages={}", output.node(), output.state().messages());
        }
    }

    private static String getenvOrDefault(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    public static class State extends MessagesState<String> {

        public State(Map<String, Object> initData) {
            super(initData);
        }

        public Optional<String> humanFeedback() {
            return value("human_feedback");
        }
    }
}
