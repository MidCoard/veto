package top.focess.veto.agent.tool.builtin;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.tool.AgentTool;
import top.focess.veto.agent.tool.Doc;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.agent.tool.ToolCapability;
import top.focess.veto.agent.tool.ToolDoc;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.agent.tool.ToolErrors;
import top.focess.veto.agent.tool.ToolExecutionException;
import top.focess.veto.agent.tool.ToolJson;
import top.focess.veto.agent.tool.ToolResultFormat;
import top.focess.veto.agent.tool.ToolResultStatus;

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
                    "Publishes one pending question batch to the session UI and pauses this agent"
                            + " call until the user answers or cancels. The UI adds a free-form Other"
                            + " choice; every answer is returned under its stable question id. Pending"
                            + " batches are in-memory and are cancelled by a backend restart.",
            whenToUse =
                    "Use it when a missing user choice materially changes the result and cannot be"
                            + " inferred safely.",
            whenNotToUse =
                    "Do not use it for permission approval, status updates, facts discoverable with"
                            + " tools, or optional preferences that do not block useful progress.",
            resultContract =
                    "Success returns JSON `{\"answers\":{\"question_id\":\"selected or entered"
                            + " value\"}}`. The `answers` object is keyed by question id. In"
                            + " detailed-result mode, cancellation has status cancelled and errorCode"
                            + " USER_CANCELLED, while invalid values use INVALID_QUESTIONS; their"
                            + " content remains actionable plaintext in every mode.",
            errorsAndEdgeCases =
                    "Provide 1-3 questions. Headers are 1-12 characters, ids are unique snake_case,"
                            + " prompts are 1-300 characters, and each question has 2-3 mutually"
                            + " exclusive options. The first option must be recommended and its label"
                            + " must end with `(Recommended)`. Labels are case-insensitively unique;"
                            + " `Other` is reserved for the UI.",
            security =
                    "A user answer does not replace any separate approval required to perform an operation.",
            examples = {
                "{\"questions\":[{\"header\":\"Format\",\"id\":\"format\",\"question\":\"Which"
                        + " output format should I use?\",\"options\":[{\"label\":\"Markdown"
                        + " (Recommended)\",\"description\":\"Easy to review and"
                        + " edit.\"},{\"label\":\"Plain text\",\"description\":\"No formatting.\"}]}]}"
            },
            returnExamples = {"{\"answers\":{\"format\":\"Markdown (Recommended)\"}}"})
    public record Args(
            @NonNull
                    @Doc(
                            "One to three questions shown together. Each object contains required"
                                    + " `header`, `id`, `question`, and `options` fields.")
                    List<@NonNull Question> questions) {}

    public record Question(
            @NonNull @Doc("Short UI heading, 1-12 Unicode characters.") String header,
            @NonNull @Doc("Unique snake_case key used in the returned answers object.") String id,
            @NonNull @Doc("One-sentence prompt, 1-300 Unicode characters.") String question,
            @NonNull @Doc("Two or three mutually exclusive choices; recommended choice first.")
                    List<@NonNull Option> options) {}

    public record Option(
            @NonNull
                    @Doc(
                            "Short choice label. The first label ends with `(Recommended)`; `Other`"
                                    + " is reserved.")
                    String label,
            @NonNull @Doc("One short sentence explaining the choice's impact.")
                    String description) {}

    @Override
    public @NonNull String getName() {
        return "ask_user";
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
        Set<String> ids = new HashSet<>();
        for (Question question : questions) {
            if (question.header().isBlank() || length(question.header()) > 12) {
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
            if (question.question().isBlank() || length(question.question()) > 300) {
                ToolErrors.failure(
                        "INVALID_QUESTIONS",
                        "Each question prompt must contain 1 to 300 characters.");
            }
            if (question.options().size() < 2 || question.options().size() > 3) {
                ToolErrors.failure("INVALID_QUESTIONS", "Each question requires 2 or 3 options.");
            }
            Set<String> labels = new HashSet<>();
            for (int index = 0; index < question.options().size(); index++) {
                Option option = question.options().get(index);
                String normalizedLabel = option.label().strip().toLowerCase(Locale.ROOT);
                if (option.label().isBlank()
                        || length(option.label()) > 40
                        || "other".equals(normalizedLabel)
                        || option.description().isBlank()
                        || length(option.description()) > 200
                        || !labels.add(normalizedLabel)) {
                    ToolErrors.failure(
                            "INVALID_QUESTIONS",
                            "Option labels must be distinct, at most 40 characters, and not"
                                    + " `Other`; descriptions must contain 1 to 200 characters.");
                }
                boolean recommended = option.label().endsWith("(Recommended)");
                if ((index == 0) != recommended) {
                    ToolErrors.failure(
                            "INVALID_QUESTIONS",
                            "Exactly the first option must end with `(Recommended)`.");
                }
            }
        }
    }

    private static int length(@NonNull String value) {
        return value.codePointCount(0, value.length());
    }
}
