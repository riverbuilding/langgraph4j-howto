package com.example.adaptiverag;

import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.model.input.structured.StructuredPrompt;
import dev.langchain4j.model.input.structured.StructuredPromptProcessor;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.structured.Description;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AnswerGrader implements Function<AnswerGrader.Arguments, AnswerGrader.Score> {

    private static final Logger log = LoggerFactory.getLogger(AnswerGrader.class);
    private static final String[] MODELS = {"gpt-3.5-turbo-0125", "gpt-4o-mini"};

    private final String apiKey;
    private final String modelName;

    public AnswerGrader() {
        this(System.getenv("OPENAI_API_KEY"), MODELS[1]);
    }

    public AnswerGrader(String apiKey, String modelName) {
        this.apiKey = apiKey;
        this.modelName = modelName;
    }

    /**
     * Binary score to assess whether the answer addresses the question.
     */
    public static class Score {

        @Description("Answer addresses the question, 'yes' or 'no'")
        public String binaryScore;

        @Override
        public String toString() {
            return "Score: " + binaryScore;
        }
    }

    @StructuredPrompt("""
            User question:

            {{question}}

            LLM generation:

            {{generation}}
            """)
    public record Arguments(String question, String generation) {
    }

    interface Service {

        @SystemMessage("""
                You are a grader assessing whether an answer addresses and/or resolves a question.

                Give a binary score 'yes' or 'no'. Yes, means that the answer resolves the question otherwise return 'no'
                """)
        Score invoke(String userMessage);
    }

    @Override
    public Score apply(Arguments args) {
        Objects.requireNonNull(args, "args must not be null");

//        if (apiKey == null || apiKey.isBlank()) {
//            throw new IllegalStateException("OPENAI_API_KEY is required to run the Adaptive RAG answer grader.");
//        }

        var chatLanguageModel = OpenAiChatModel.builder()
                .baseUrl("http://langchain4j.dev/demo/openai/v1")
                .apiKey("demo")
                //.apiKey(apiKey)
                .modelName(modelName)
                .timeout(Duration.ofMinutes(2))
                .logRequests(true)
                .logResponses(true)
                .maxRetries(2)
                .temperature(0.0)
                .maxTokens(2000)
                .build();

        Service service = AiServices.create(Service.class, chatLanguageModel);
        Prompt prompt = StructuredPromptProcessor.toPrompt(args);

        log.trace("prompt: {}", prompt.text());

        return service.invoke(prompt.text());
    }
}
