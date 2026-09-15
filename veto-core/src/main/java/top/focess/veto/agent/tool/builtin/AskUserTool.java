package top.focess.veto.agent.tool.builtin;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.capability.UserInteractionCapability;
import top.focess.veto.agent.tool.ArraySize;
import top.focess.veto.agent.tool.Doc;
import top.focess.veto.agent.tool.StringConstraint;
import top.focess.veto.agent.tool.ToolDoc;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.agent.tool.ToolErrors;
import top.focess.veto.agent.tool.ToolExecutionException;
import top.focess.veto.agent.tool.ToolJson;
import top.focess.veto.agent.tool.ToolResultFormat;
import top.focess.veto.agent.tool.ToolResultStatus;
import top.focess.veto.agent.tool.UserInteractionTool;

/** Pauses the calling agent for a batch of up to {@value #MAX_QUESTIONS} questions. */
@Component
@ToolDoc(
        resultFormats = {ToolResultFormat.JSON},
        description =
                "Ask the user up to "
                        + AskUserTool.MAX_QUESTIONS
                        + " short questions and wait for their answers.",
        behavior =
                "Publishes one pending question batch to the session UI and pauses this agent"
                        + " call until the user answers or cancels. The UI adds a free-form Other"
                        + " choice; every answer is returned under its stable question id. Pending"
                        + " batches are in-memory and are cancelled by a backend restart.",
        whenToUse =
                "Use it when a missing user choice materially changes the result and cannot be"
                        + " inferred safely. Usually ask 1-3 questions; combine additional independent questions"
                        + " when needed, up to "
                        + AskUserTool.MAX_QUESTIONS
                        + ". Ask dependent questions in separate batches after receiving earlier answers.",
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
                "Provide 1-"
                        + AskUserTool.MAX_QUESTIONS
                        + " questions. Headers are 1-12 characters, ids are unique snake_case,"
                        + " prompts are 1-300 characters, and each question has 2-5 mutually"
                        + " exclusive options. Put the recommended option first; the application adds"
                        + " its recommendation marker. Send plain labels without `(Recommended)`. Labels are case-insensitively unique;"
                        + " `Other` is reserved for the UI. Labels contain 1-120 Unicode characters"
                        + " excluding the application-added recommendation marker; descriptions contain 1-200 characters.",
        security =
                "A user answer does not replace any separate approval required to perform an operation.",
        examples = {
            "{\"questions\":[{\"header\":\"Format\",\"id\":\"format\",\"question\":\"Which"
                    + " output format should I use?\",\"options\":[{\"label\":\"Markdown"
                    + "\",\"description\":\"Easy to review and"
                    + " edit.\"},{\"label\":\"Plain text\",\"description\":\"No formatting.\"}]}]}"
        },
        returnExamples = {"{\"answers\":{\"format\":\"Markdown\"}}"})
public final class AskUserTool implements UserInteractionTool<AskUserTool.Args> {

    static final int MAX_QUESTIONS = 10;
    static final int MIN_OPTIONS = 2;
    static final int MAX_OPTIONS = 5;

    private final @NonNull UserInteractionCapability capability;

    public AskUserTool(@NonNull UserInteractionCapability capability) {
        this.capability = capability;
    }

    public record Args(
            @ArraySize(min = 1, max = MAX_QUESTIONS)
                    @NonNull
                    @Doc(
                            "One to "
                                    + MAX_QUESTIONS
                                    + " questions shown together. Each object contains required"
                                    + " `header`, `id`, `question`, and `options` fields.")
                    List<@NonNull Question> questions) {}

    public record Question(
            @StringConstraint(minLength = 1, maxLength = 12)
                    @NonNull
                    @Doc("Short UI heading, 1-12 Unicode characters.")
                    String header,
            @StringConstraint(minLength = 1, pattern = "^[a-z][a-z0-9_]*$")
                    @NonNull
                    @Doc("Unique snake_case key used in the returned answers object.")
                    String id,
            @StringConstraint(minLength = 1, maxLength = 300)
                    @NonNull
                    @Doc("One-sentence prompt, 1-300 Unicode characters.")
                    String question,
            @ArraySize(min = MIN_OPTIONS, max = MAX_OPTIONS)
                    @NonNull
                    @Doc(
                            "Two to five mutually exclusive choices. Put the recommended choice first; the application adds the recommendation marker. Do not add it to labels.")
                    List<@NonNull Option> options) {}

    public record Option(
            @StringConstraint(minLength = 1, maxLength = 120)
                    @NonNull
                    @Doc(
                            "Plain choice label, 1-120 Unicode characters. The application marks the first option as recommended; do not write `(Recommended)` yourself. Move explanations into the description; `Other`"
                                    + " is reserved.")
                    String label,
            @StringConstraint(minLength = 1, maxLength = 200)
                    @NonNull
                    @Doc(
                            "One short sentence, 1-200 Unicode characters, explaining the choice's impact.")
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
    public @NonNull UserInteractionCapability userInteractionCapability() {
        return capability;
    }

    @Override
    public @NonNull String execute(
            @NonNull Args args, @NonNull UserInteractionCapability capability) throws Exception {
        validate(args.questions());

        UserQuestionRegistry.AnswerBatch answer;
        try {
            answer = capability.ask(args.questions());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolExecutionException(
                    ToolResultStatus.CANCELLED,
                    ToolResultFormat.PLAINTEXT,
                    "TOOL_INTERRUPTED",
                    "The question was interrupted.");
        }
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
        if (questions.isEmpty() || questions.size() > MAX_QUESTIONS) {
            ToolErrors.failure(
                    "INVALID_QUESTIONS",
                    "ask_user requires between 1 and " + MAX_QUESTIONS + " questions.");
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
            if (question.options().size() < MIN_OPTIONS
                    || question.options().size() > MAX_OPTIONS) {
                ToolErrors.failure(
                        "INVALID_QUESTIONS",
                        "Question '"
                                + question.id()
                                + "' has "
                                + question.options().size()
                                + " options; expected "
                                + MIN_OPTIONS
                                + " to "
                                + MAX_OPTIONS
                                + ". Revise the options and call ask_user again. No questions were sent and no answers were collected.");
            }
            Set<String> labels = new HashSet<>();
            for (int index = 0; index < question.options().size(); index++) {
                Option option = question.options().get(index);
                String normalizedLabel = option.label().strip().toLowerCase(Locale.ROOT);
                if (length(option.label()) > 120) {
                    ToolErrors.failure(
                            "INVALID_QUESTIONS",
                            "Question '"
                                    + question.id()
                                    + "', option "
                                    + (index + 1)
                                    + ": label has "
                                    + length(option.label())
                                    + " characters; maximum is 120 in the supplied label."
                                    + " Shorten the label and call ask_user again; no questions were sent.");
                }
                if (option.label().isBlank()
                        || "other".equals(normalizedLabel)
                        || option.description().isBlank()
                        || length(option.description()) > 200
                        || !labels.add(normalizedLabel)) {
                    ToolErrors.failure(
                            "INVALID_QUESTIONS",
                            "Option labels must be distinct, at most 120 characters, and not"
                                    + " `Other`; descriptions must contain 1 to 200 characters.");
                }
            }
        }
    }

    private static int length(@NonNull String value) {
        return value.codePointCount(0, value.length());
    }
}
