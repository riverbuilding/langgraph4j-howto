package com.example.parallelbranch;

import java.util.Map;
import java.util.Objects;
import org.bsc.async.AsyncGenerator;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphRepresentation;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.prebuilt.MessagesStateGraph;
import org.bsc.langgraph4j.utils.EdgeMappings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

public class ParallelBranchApplication {

    private static final Logger log = LoggerFactory.getLogger(ParallelBranchApplication.class);

    public static void main(String[] args) throws GraphStateException {
        run("basic parallel branches", basicParallelBranches());
        run("conditional parallel branch", conditionalParallelBranch());
        run("parallel branches with two subgraphs", parallelBranchesWithTwoSubgraphs());
        run("parallel branches with three subgraphs", parallelBranchesWithThreeSubgraphs());
    }

    private static CompiledGraph<MessagesState<String>> basicParallelBranches() throws GraphStateException {
        return new MessagesStateGraph<String>()
                .addNode("A", makeNode("A"))
                .addNode("A1", makeNode("A1"))
                .addNode("A2", makeNode("A2"))
                .addNode("A3", makeNode("A3"))
                .addNode("B", makeNode("B"))
                .addNode("C", makeNode("C"))
                .addEdge("A", "A1")
                .addEdge("A", "A2")
                .addEdge("A", "A3")
                .addEdge("A1", "B")
                .addEdge("A2", "B")
                .addEdge("A3", "B")
                .addEdge("B", "C")
                .addEdge(START, "A")
                .addEdge("C", END)
                .compile();
    }

    private static CompiledGraph<MessagesState<String>> conditionalParallelBranch() throws GraphStateException {
        return new MessagesStateGraph<String>()
                .addNode("A", makeNode("A"))
                .addNode("A1", makeNode("A1"))
                .addNode("A2", makeNode("A2"))
                .addNode("A3", makeNode("A3"))
                .addNode("B", makeNode("B"))
                .addNode("C", makeNode("C"))
                .addEdge("A", "A1")
                .addEdge("A", "A2")
                .addEdge("A", "A3")
                .addEdge("A1", "B")
                .addEdge("A2", "B")
                .addEdge("A3", "B")
                .addConditionalEdges("B",
                        edge_async(state -> state.lastMinus(1)
                                .filter(message -> Objects.equals(message, "A3"))
                                .map(message -> "continue")
                                .orElse("back")),
                        EdgeMappings.builder()
                                .to("A1", "back")
                                .to("C", "continue")
                                .build())
                .addEdge(START, "A")
                .addEdge("C", END)
                .compile();
    }

    private static CompiledGraph<MessagesState<String>> parallelBranchesWithTwoSubgraphs() throws GraphStateException {
        CompiledGraph<MessagesState<String>> subgraphA1 = createTwoStepSubgraph("A1");
        CompiledGraph<MessagesState<String>> subgraphA3 = createTwoStepSubgraph("A3");

        return new MessagesStateGraph<String>()
                .addNode("A", makeNode("A"))
                .addNode("A1", subgraphA1)
                .addNode("A2", makeNode("A2"))
                .addNode("A3", subgraphA3)
                .addNode("B", makeNode("B"))
                .addEdge("A", "A1")
                .addEdge("A", "A2")
                .addEdge("A", "A3")
                .addEdge("A1", "B")
                .addEdge("A2", "B")
                .addEdge("A3", "B")
                .addEdge(START, "A")
                .addEdge("B", END)
                .compile();
    }

    private static CompiledGraph<MessagesState<String>> parallelBranchesWithThreeSubgraphs() throws GraphStateException {
        CompiledGraph<MessagesState<String>> subgraphA1 = createTwoStepSubgraph("A1");
        CompiledGraph<MessagesState<String>> subgraphA2 = createTwoStepSubgraph("A2");
        CompiledGraph<MessagesState<String>> subgraphA3 = createTwoStepSubgraph("A3");

        return new MessagesStateGraph<String>()
                .addNode("A", makeNode("A"))
                .addNode("A1", subgraphA1)
                .addNode("A2", subgraphA2)
                .addNode("A3", subgraphA3)
                .addNode("B", makeNode("B"))
                .addEdge("A", "A1")
                .addEdge("A", "A2")
                .addEdge("A", "A3")
                .addEdge("A1", "B")
                .addEdge("A2", "B")
                .addEdge("A3", "B")
                .addEdge(START, "A")
                .addEdge("B", END)
                .compile();
    }

    private static CompiledGraph<MessagesState<String>> createTwoStepSubgraph(String prefix) throws GraphStateException {
        return new MessagesStateGraph<String>()
                .addNode(prefix + ".1", makeNode(prefix + ".1"))
                .addNode(prefix + ".2", makeNode(prefix + ".2"))
                .addEdge(START, prefix + ".1")
                .addEdge(prefix + ".1", prefix + ".2")
                .addEdge(prefix + ".2", END)
                .compile();
    }

    private static AsyncNodeAction<MessagesState<String>> makeNode(String message) {
        return node_async(state -> Map.of("messages", message));
    }

    private static void run(String title, CompiledGraph<MessagesState<String>> workflow) {
        log.info("Running {}", title);
        logGraph(title, workflow.getGraph(GraphRepresentation.Type.PLANTUML, title, false));

        AsyncGenerator<NodeOutput<MessagesState<String>>> steps = workflow.stream(Map.of());
        for (NodeOutput<MessagesState<String>> step : steps) {
            log.info("{}", step);
        }
    }

    private static void logGraph(String title, GraphRepresentation representation) {
        log.info("{} PlantUML:\n{}", title, representation.getContent());
    }
}
