package com.example.adaptiverag;

import java.util.List;

public class AdaptiveRagApplication {

    public static void main(String[] args) {
        var grader = new AnswerGrader();

        var examples = List.of(
                new AnswerGrader.Arguments(
                        "What are the four operations ? ",
                        "LLM means Large Language Model"),
                new AnswerGrader.Arguments(
                        "What are the four operations",
                        "There are four basic operations: addition, subtraction, multiplication, and division."),
                new AnswerGrader.Arguments(
                        "What player at the Bears expected to draft first in the 2024 NFL draft?",
                        "The Bears selected USC quarterback Caleb Williams with the No. 1 pick in the 2024 NFL Draft."));

        for (var example : examples) {
            System.out.printf("Question: %s%n", example.question());
            System.out.printf("Generation: %s%n", example.generation());
            System.out.printf("%s%n%n", grader.apply(example));
        }
    }
}
