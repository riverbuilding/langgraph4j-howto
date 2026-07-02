package com.example.subgraphascompiledgraph;

import java.util.Map;
import org.bsc.async.AsyncGenerator;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphRepresentation;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

public class SubgraphAsCompiledGraphApplication {

    private static final Logger log = LoggerFactory.getLogger(SubgraphAsCompiledGraphApplication.class);

    public static void main(String[] args) throws GraphStateException {
        StateGraph<State> workflowChild = createChildWorkflow();
        CompiledGraph<State> compiledWorkflowChild = workflowChild.compile();
        StateGraph<State> workflow = createWorkflow(compiledWorkflowChild);
        CompiledGraph<State> compiledWorkflow = workflow.compile();

        log.info("Running parent workflow with child CompiledGraph node");
        AsyncGenerator<NodeOutput<State>> steps = compiledWorkflow.stream(Map.of());
        for (NodeOutput<State> step : steps) {
            log.info("{}", step);
        }

        //logGraph("sub graph", compiledWorkflow.getGraph(GraphRepresentation.Type.PLANTUML, "sub graph", false));
        //logGraph("merged sub graph",
                //compiledWorkflow.getGraph(GraphRepresentation.Type.PLANTUML, "merged sub graph", false));
    }

    private static StateGraph<State> createChildWorkflow() throws GraphStateException {
        return new StateGraph<>(State.SCHEMA, State::new)
                .addNode("child:step_1", makeNode("child:step1"))
                .addNode("child:step_2", makeNode("child:step2"))
                .addNode("child:step_3", makeNode("child:step3"))
                .addEdge(START, "child:step_1")
                .addEdge("child:step_1", "child:step_2")
                .addConditionalEdges("child:step_2",
                        edge_async(state -> "continue"),
                        Map.of(END, END, "continue", "child:step_3"))
                .addEdge("child:step_3", END);
    }

    private static StateGraph<State> createWorkflow(CompiledGraph<State> workflowChild) throws GraphStateException {
        return new StateGraph<>(State.SCHEMA, State::new)
                .addNode("step_1", makeNode("step1"))
                .addNode("step_2", makeNode("step2"))
                .addNode("step_3", makeNode("step3"))
                .addNode("subgraph", workflowChild)
                .addEdge(START, "step_1")
                .addEdge("step_1", "step_2")
                .addEdge("step_2", "subgraph")
                .addEdge("subgraph", "step_3")
                .addEdge("step_3", END);
    }

    private static AsyncNodeAction<State> makeNode(String id) {
        return node_async(state -> Map.of("messages", id));
    }

    private static void logGraph(String title, GraphRepresentation representation) {
        log.info("{} PlantUML:\n{}", title, representation.getContent());
    }

    public static class State extends MessagesState<String> {

        public State(Map<String, Object> initData) {
            super(initData);
        }
    }
}
