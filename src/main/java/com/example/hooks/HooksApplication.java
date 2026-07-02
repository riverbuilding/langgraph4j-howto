package com.example.hooks;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncNodeActionWithConfig;
import org.bsc.langgraph4j.hook.NodeHook;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.utils.CollectionsUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.bsc.langgraph4j.GraphDefinition.END;
import static org.bsc.langgraph4j.GraphDefinition.START;
import static org.bsc.langgraph4j.action.AsyncNodeActionWithConfig.node_async;

public class HooksApplication {

    private static final Logger log = LoggerFactory.getLogger(HooksApplication.class);

    public static void main(String[] args) throws GraphStateException {
        CompiledGraph<State> workflow = new StateGraph<>(MessagesState.SCHEMA, State::new)
                .addWrapCallNodeHook(new LoggingNodeHook<>(log))
                .addNode("node_1", simpleAction())
                .addNode("node_2", simpleAction())
                .addNode("node_3", simpleAction())
                .addNode("node_4", simpleAction())
                .addEdge(START, "node_1")
                .addEdge("node_1", "node_2")
                .addEdge("node_2", "node_3")
                .addEdge("node_3", "node_4")
                .addEdge("node_4", END)
                .compile();

        State result = workflow.invoke(GraphInput.noArgs(), RunnableConfig.builder().build())
                .orElseThrow(() -> new IllegalStateException("Workflow returned no state"));

        log.info("Workflow execution result: {}", result);
    }

    private static AsyncNodeActionWithConfig<State> simpleAction() {
        return node_async((state, config) -> Map.of("messages", config.nodeId()));
    }

    public record LoggingNodeHook<S extends AgentState>(Logger log)
            implements NodeHook.WrapCall<S> {

        @Override
        public CompletableFuture<Map<String, Object>> applyWrap(
                String nodeId,
                S state,
                RunnableConfig config,
                AsyncNodeActionWithConfig<S> action) {

            log.info("node action for node '{}' start with state:\n{}", nodeId, state);
            return action.apply(state, config)
                    .whenComplete((result, exception) ->
                            log.info("node action for node '{}' end with requested update:\n{}",
                                    nodeId,
                                    CollectionsUtils.toString(result)));
        }
    }

    public static class State extends MessagesState<String> {

        public State(Map<String, Object> initData) {
            super(initData);
        }
    }
}
