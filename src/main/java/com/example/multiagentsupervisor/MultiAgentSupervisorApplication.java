package com.example.multiagentsupervisor;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.structured.Description;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.V;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import net.sourceforge.plantuml.FileFormat;
import net.sourceforge.plantuml.FileFormatOption;
import net.sourceforge.plantuml.SourceStringReader;
import org.bsc.async.AsyncGenerator;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphRepresentation;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.NodeAction;
import org.bsc.langgraph4j.langchain4j.serializer.std.ChatMesssageSerializer;
import org.bsc.langgraph4j.langchain4j.serializer.std.ToolExecutionRequestSerializer;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.bsc.langgraph4j.utils.EdgeMappings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static dev.langchain4j.model.openai.OpenAiChatModelName.GPT_4_O_MINI;
import static java.lang.String.format;
import static java.time.Duration.ofSeconds;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

public class MultiAgentSupervisorApplication {

    private static final Logger log = LoggerFactory.getLogger(MultiAgentSupervisorApplication.class);

    public static void main(String[] args) throws Exception {
//        String ollamaBaseUrl = envOrDefault("OLLAMA_BASE_URL", "http://localhost:11434");
//        String supervisorModelName = envOrDefault("SUPERVISOR_MODEL", "gpt-oss:20b");
//        String workerModelName = envOrDefault("WORKER_MODEL", "qwen2.5:7b");

//        ChatModel supervisorModel = OllamaChatModel.builder()
//                .baseUrl(ollamaBaseUrl)
//                .temperature(0.0)
//                .logRequests(true)
//                .logResponses(true)
//                .modelName(supervisorModelName)
//                .build();
        ChatModel supervisorModel = OpenAiChatModel.builder()
                .baseUrl("http://langchain4j.dev/demo/openai/v1")
                .apiKey("demo")
                .modelName(GPT_4_O_MINI)
                //.logRequests(true)
                .timeout(ofSeconds(60))
                .build();
//        ChatModel workerModel = OllamaChatModel.builder()
//                .baseUrl(ollamaBaseUrl)
//                .temperature(0.0)
//                .logRequests(true)
//                .logResponses(true)
//                .modelName(workerModelName)
//                .build();
        ChatModel workerModel = OpenAiChatModel.builder()
                .baseUrl("http://langchain4j.dev/demo/openai/v1")
                .apiKey("demo")
                .modelName(GPT_4_O_MINI)
                //.logRequests(true)
                .timeout(ofSeconds(60))
                .build();


        StateGraph<State> workflow = createWorkflow(supervisorModel, workerModel);
        //writeGraphRepresentation(workflow);

        CompiledGraph<State> graph = workflow.compile();

        runQuestion(graph, "what are the result of 1 + 1 ?");
        //runQuestion(graph, "where are next winter olympic games ?");
    }

    private static StateGraph<State> createWorkflow(ChatModel supervisorModel, ChatModel workerModel)
            throws GraphStateException {

        SupervisorAgent supervisor = new SupervisorAgent(supervisorModel);
        CoderAgent coder = new CoderAgent(workerModel);
        ResearchAgent researcher = new ResearchAgent(workerModel);

        return new StateGraph<>(State.SCHEMA, new StateSerializer())
                .addNode("supervisor", node_async(supervisor))
                .addNode("coder", node_async(coder))
                .addNode("researcher", node_async(researcher))
                .addEdge(START, "supervisor")
                .addConditionalEdges(
                        "supervisor",
                        edge_async(state -> state.next().orElseThrow()),
                        EdgeMappings.builder()
                                .to("coder")
                                .to("researcher")
                                .toEND("FINISH")
                                .build())
                .addEdge("coder", "supervisor")
                .addEdge("researcher", "supervisor");
    }

    private static void runQuestion(CompiledGraph<State> graph, String question) {
        log.info("QUESTION: {}", question);

        Map<String, Object> input = Map.of("messages", UserMessage.from(question));
        AsyncGenerator<NodeOutput<State>> events = graph.stream(input);

        for (NodeOutput<State> event : events) {
            log.info("{}", event);
        }
    }

    private static void writeGraphRepresentation(StateGraph<State> workflow) throws IOException {
        GraphRepresentation representation = workflow.getGraph(GraphRepresentation.Type.PLANTUML, "sub graph", false);
        log.info(representation.content());
//        Path targetDir = Path.of("target");
//        Files.createDirectories(targetDir);
//        Files.writeString(targetDir.resolve("multi-agent-supervisor.puml"), representation.getContent());
//
//        SourceStringReader reader = new SourceStringReader(representation.getContent());
//        try (ByteArrayOutputStream imageOutStream = new ByteArrayOutputStream()) {
//            reader.outputImage(imageOutStream, 0, new FileFormatOption(FileFormat.PNG));
//            Files.write(targetDir.resolve("multi-agent-supervisor.png"), imageOutStream.toByteArray());
//        }
    }

    private static String envOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    public static class State extends MessagesState<ChatMessage> {

        public State(Map<String, Object> initData) {
            super(initData);
        }

        public Optional<String> next() {
            return this.value("next");
        }
    }

    public static class StateSerializer extends ObjectStreamStateSerializer<State> {

        public StateSerializer() {
            super(State::new);
            mapper().register(ToolExecutionRequest.class, new ToolExecutionRequestSerializer());
            mapper().register(ChatMessage.class, new ChatMesssageSerializer());
        }
    }

    public static class SupervisorAgent implements NodeAction<State> {

        private final Service service;
        private final String[] members = {"researcher", "coder"};

        public SupervisorAgent(ChatModel model) {
            service = AiServices.create(Service.class, model);
        }

        @Override
        public Map<String, Object> apply(State state) {
            ChatMessage message = state.lastMessage().orElseThrow();
            String text = messageText(message);
            String memberList = String.join(",", members);
            Router result = service.evaluate(memberList, text);

            return Map.of("next", result.next);
        }

        public static class Router {

            @Description("Worker to route to next. If no workers needed, route to FINISH.")
            public String next;

            @Override
            public String toString() {
                return format("Router[next: %s]", next);
            }
        }

        interface Service {

            @SystemMessage("""
                    You are a supervisor tasked with managing a conversation between the following workers: {{members}}.
                    Given the following user request, respond with the worker to act next.
                    Each worker will perform a task and respond with their results and status.
                    When finished, respond with FINISH.
                    """)
            Router evaluate(@V("members") String members, @dev.langchain4j.service.UserMessage String userMessage);
        }
    }

    public static class ResearchAgent implements NodeAction<State> {

        private final Service service;

        public ResearchAgent(ChatModel model) {
            service = AiServices.builder(Service.class)
                    .chatModel(model)
                    .tools(new Tools())
                    .build();
        }

        @Override
        public Map<String, Object> apply(State state) {
            ChatMessage message = state.lastMessage().orElseThrow();
            String result = service.search(messageText(message));
            return Map.of("messages", AiMessage.from(result));
        }

        static class Tools {

            @Tool("""
                    Use this to perform a research over internet
                    """)
            String search(@P("internet query") String query) {
                log.info("search query: '{}'", query);
                return """
                        the games will be in Italy at Cortina '2026
                        """;
            }
        }

        interface Service {
            String search(@dev.langchain4j.service.UserMessage String query);
        }
    }

    public static class CoderAgent implements NodeAction<State> {

        private final Service service;

        public CoderAgent(ChatModel model) {
            service = AiServices.builder(Service.class)
                    .chatModel(model)
                    .tools(new Tools())
                    .build();
        }

        @Override
        public Map<String, Object> apply(State state) {
            ChatMessage message = state.lastMessage().orElseThrow();
            String result = service.evaluate(messageText(message));
            return Map.of("messages", AiMessage.from(result));
        }

        static class Tools {

            @Tool("""
                    Use this to execute java code and do math. If you want to see the output of a value,
                    you should print it out with `System.out.println(...);`. This is visible to the user.
                    """)
            String search(@P("coder request") String request) {
                log.info("CoderTool request: '{}'", request);
                return """
                        2
                        """;
            }
        }

        interface Service {
            String evaluate(@dev.langchain4j.service.UserMessage String code);
        }
    }

    private static String messageText(ChatMessage message) {
        return switch (message.type()) {
            case USER -> ((UserMessage) message).singleText();
            case AI -> ((AiMessage) message).text();
            default -> throw new IllegalStateException("unexpected message type: " + message.type());
        };
    }
}
