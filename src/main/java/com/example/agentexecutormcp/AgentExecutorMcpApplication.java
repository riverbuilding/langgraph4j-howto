package com.example.agentexecutormcp;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.McpReadResourceResult;
import dev.langchain4j.mcp.client.McpResource;
import dev.langchain4j.mcp.client.McpResourceContents;
import dev.langchain4j.mcp.client.McpTextResourceContents;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.model.ollama.OllamaChatModel;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import dev.langchain4j.model.openai.OpenAiChatModel;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.agentexecutor.AgentExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AgentExecutorMcpApplication {

    private static final Logger log = LoggerFactory.getLogger(AgentExecutorMcpApplication.class);

    public static void main(String[] args) throws Exception {
        String postgresUrl = envOrDefault(
                "MCP_POSTGRES_URL",
                "postgresql://admin:bsorrentino@host.docker.internal:5432/mcp_db");
//        String ollamaBaseUrl = envOrDefault("OLLAMA_BASE_URL", "http://localhost:11434");
//        String ollamaModel = envOrDefault("OLLAMA_MODEL", "qwen2.5-coder:latest");
        String question = envOrDefault("MCP_QUESTION", "get all issues names and project");

        StdioMcpTransport transport = StdioMcpTransport.builder()
                .command(List.of(
                        "docker",
                        "run",
                        "-i",
                        "--rm",
                        "mcp/postgres",
                        postgresUrl))
                .logEvents(true)
                .environment(Map.of())
                .build();

        McpClient mcpClient = DefaultMcpClient.builder()
                .transport(transport)
                .build();

        try {
            String schemaContext = buildSchemaContext(mcpClient);
            log.info("MCP schema context:\n{}", schemaContext);

            var model = OpenAiChatModel.builder()
                    .baseUrl("http://langchain4j.dev/demo/openai/v1")
                    .apiKey("demo")
                    .modelName("gpt-4o-mini")
                    .timeout(Duration.ofMinutes(2))
                    .logRequests(true)
                    .logResponses(true)
                    .maxRetries(2)
                    .temperature(0.0)
                    .maxTokens(2000)
                    .build();


            AgentExecutor.Builder agentBuilder = AgentExecutor.builder()
                    .chatModel(model);

            agentBuilder.tool(mcpClient);
            var a = agentBuilder.build();
            CompiledGraph<AgentExecutor.State> agent = agentBuilder.build().compile();
            UserMessage message = createQuestionMessage(schemaContext, question);

            String response = agent.invoke(Map.of("messages", message))
                    .flatMap(AgentExecutor.State::finalResponse)
                    .orElse("no response");

            log.info("Agent response:\n{}", response);
        } finally {
            mcpClient.close();
        }
    }

    private static String buildSchemaContext(McpClient mcpClient) {
        List<McpResource> tableResources = mcpClient.listResources().stream().toList();
        List<String> columnResources = tableResources.stream()
                .map(resource -> mcpClient.readResource(resource.uri()))
                .map(McpReadResourceResult::contents)
                .flatMap(List::stream)
                .filter(content -> content.type() == McpResourceContents.Type.TEXT)
                .map(McpTextResourceContents.class::cast)
                .map(McpTextResourceContents::text)
                .toList();

        StringBuilder context = new StringBuilder();
        for (int i = 0; i < tableResources.size(); i++) {
            context.append(tableResources.get(i).name())
                    .append(" = ");

            if (i < columnResources.size()) {
                context.append(columnResources.get(i));
            } else {
                context.append("schema unavailable");
            }

            context.append("\n\n");
        }

        return context.toString();
    }

    private static UserMessage createQuestionMessage(String schemaContext, String question) {
        PromptTemplate prompt = PromptTemplate.from(
                """
                You have access to the following tables:

                {{schema}}

                Answer the question using the tables above.

                {{input}}
                """);

        return prompt.apply(Map.of(
                        "schema", schemaContext,
                        "input", question))
                .toUserMessage();
    }

    private static String envOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
