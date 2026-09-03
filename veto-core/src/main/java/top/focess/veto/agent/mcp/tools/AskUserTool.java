package top.focess.veto.agent.mcp.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.mcp.AgentTool;
import top.focess.veto.agent.mcp.Doc;
import top.focess.veto.agent.mcp.ToolCallContextHolder;
import top.focess.veto.agent.mcp.ToolCapability;
import top.focess.veto.agent.mcp.ToolDoc;
import top.focess.veto.agent.mcp.ToolDocs;
import top.focess.veto.agent.mcp.ToolErrors;
import top.focess.veto.agent.mcp.ToolExecutionException;
import top.focess.veto.agent.mcp.ToolJson;
import top.focess.veto.agent.mcp.ToolResultFormat;
import top.focess.veto.agent.mcp.ToolResultStatus;

/** Pauses the calling agent until the user answers one to three short questions. */
@Component
public final class AskUserTool implements AgentTool<AskUserTool.Args> {

    private final @NonNull UserQuestionRegistry registry;

    public AskUserTool(@NonNull UserQuestionRegistry registry) {
        this.registry = registry;
    }

    @ToolDoc(
            resultFormats = {ToolResultFormat.JSON},
            description = "Ask the user one to three short questions and wait for their answers.",
            behavior =
                    "Publishes one pending question batch to the session UI and pauses this agent call until the user answers or cancels. "
                            + "The UI adds a free-form Other choice; every answer is returned under its stable question id.",
            whenToUse =
                    "Use it when a missing user choice materially changes the result and cannot be inferred safely.",
            whenNotToUse =
                    "Do not use it for permission approval, status updates, facts discoverable with tools, or optional preferences that do not block useful progress.",
            resultContract =
                    "Success returns JSON `{"
                            + "\"answers\":{\"question_id\":\"selected or entered value\"}}`. User cancellation returns status CANCELLED with errorCode USER_CANCELLED.",
            errorsAndEdgeCases =
                    "Provide 1-3 questions. Each question has a short header, stable id, one sentence prompt, and 2-3 mutually exclusive options. "
                            + "Labels must be distinct within a question and one option should be the recommended choice.",
            security =
                    "Agent-runtime user interaction. It does not bypass or replace Gateway human approval.",
            examples = {
                "{\"questions\":[{\"header\":\"Format\",\"id\":\"format\",\"question\":\"Which output format should I use?\",\"options\":[{\"label\":\"Markdown (Recommended)\",\"description\":\"Easy to review and edit.\"},{\"label\":\"Plain text\",\"description\":\"No formatting.\"}]}]}"
            },
            returnExamples = {"{\"answers\":{\"format\":\"Markdown (Recommended)\"}}"})
    public record Args(
            @NonNull @Doc("One to three questions shown together.")
                    List<@NonNull Question> questions) {}

    public record Question(
            @NonNull String header,
            @NonNull String id,
            @NonNull String question,
            @NonNull List<@NonNull Option> options) {}

    public record Option(@NonNull String label, @NonNull String description) {}

    @Override
    public @NonNull String getName() {
        return "ask_user";
    }

    @Override
    public @NonNull String getDescription() {
        return "Ask the user one to three short questions and wait for their answers.";
    }

    @Override
    public @NonNull Class<Args> getArgsClass() {
        return ToolDocs.nonNullClass(Args.class);
    }

    @Override
    public @NonNull ToolCapability getCapability() {
        return ToolCapability.USER_INTERACTION;
    }

    @Override
    public @NonNull String execute(@NonNull Args args) {
        validate(args.questions());
        var context = ToolCallContextHolder.get();
        String callId = ToolCallContextHolder.currentCallId();
        if (context == null || callId == null || callId.isBlank()) {
            throw new SecurityException("ask_user requires an active agent tool call");
        }
        UserQuestionRegistry.AnswerBatch answer =
                registry.register(context.agentId(), callId, List.copyOf(args.questions())).join();
        if (answer.cancelled()) {
            throw new ToolExecutionException(
                    ToolResultStatus.CANCELLED,
                    ToolResultFormat.PLAINTEXT,
                    "USER_CANCELLED",
                    "The user cancelled the question.");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answers", answer.answers());
        return ToolJson.object(result);
    }

    private static void validate(@NonNull List<@NonNull Question> questions) {
        if (questions.isEmpty() || questions.size() > 3) {
            ToolErrors.failure("INVALID_QUESTIONS", "ask_user requires between 1 and 3 questions.");
        }
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (Question question : questions) {
            if (question.header().isBlank() || question.header().length() > 12) {
                ToolErrors.failure(
                        "INVALID_QUESTIONS",
                        "Each question header must contain 1 to 12 characters.");
            }
            if (question.id().isBlank()
                    || !question.id().matches("[a-z][a-z0-9_]*")
                    || !ids.add(question.id())) {
                ToolErrors.failure(
                        "INVALID_QUESTIONS", "Question ids must be unique snake_case identifiers.");
            }
            if (question.question().isBlank()) {
                ToolErrors.failure("INVALID_QUESTIONS", "Each question prompt must be non-blank.");
            }
            if (question.options().size() < 2 || question.options().size() > 3) {
                ToolErrors.failure("INVALID_QUESTIONS", "Each question requires 2 or 3 options.");
            }
            java.util.Set<String> labels = new java.util.HashSet<>();
            for (Option option : question.options()) {
                if (option.label().isBlank()
                        || option.description().isBlank()
                        || !labels.add(option.label())) {
                    ToolErrors.failure(
                            "INVALID_QUESTIONS",
                            "Option labels must be distinct and all option fields must be non-blank.");
                }
            }
        }
    }
}
