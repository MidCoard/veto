package top.focess.veto.builtin.tools;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.screening.Danger;
import top.focess.veto.api.agent.tool.AgentTool;
import top.focess.veto.api.agent.tool.ArraySize;
import top.focess.veto.api.agent.tool.Doc;
import top.focess.veto.api.agent.tool.NativeTool;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolDoc;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.agent.tool.ToolErrorCode;
import top.focess.veto.api.agent.tool.ToolErrors;
import top.focess.veto.api.agent.tool.ToolExecutionException;
import top.focess.veto.api.agent.tool.ToolJson;
import top.focess.veto.api.agent.tool.ToolResultFormat;
import top.focess.veto.api.agent.tool.ToolResultStatus;
import top.focess.veto.api.agent.tool.ToolSecurity;
import top.focess.veto.builtin.questions.AnswerBatch;
import top.focess.veto.builtin.questions.Option;
import top.focess.veto.builtin.questions.Question;
import top.focess.veto.builtin.questions.QuestionRuntime;

/** Pauses the calling agent for a batch of up to {@value #MAX_QUESTIONS} questions. */
@ToolDoc(
        resultFormats = {ToolResultFormat.JSON},
        description =
                "Ask the user for necessary information or a requested interview step, then wait for their answers. Follow the requested question count and pacing; use the answers to continue the task.",
        behavior =
                """
                Publishes one pending question batch to the session UI and pauses this agent call \
                until the user answers or cancels. The UI adds a free-form Other choice; every \
                answer is returned under its stable question id. Pending batches are in-memory and \
                are cancelled by a backend restart.""",
        whenToUse =
                """
                Use it when a missing user choice materially changes the result and cannot be \
                inferred safely, or when the user explicitly requests an interview or guided \
                choice. Batch independent questions when useful; wait for earlier answers before \
                asking dependent questions.""",
        whenNotToUse =
                """
                Do not use it for permission approval, status updates, facts discoverable with \
                tools, or unsolicited optional preferences that do not block useful progress. \
                Preferences are relevant when learning them is the user's requested task.""",
        resultContract =
                """
                Returns JSON `{"answers":{"question_id":"selected or entered value"}}`. Invalid \
                questions fail with INVALID_QUESTIONS and `Invalid questions: <detail>`; \
                cancellation reports USER_CANCELLED \
                (`Cancelled: the user cancelled the question batch.`), and an interrupted wait \
                reports TOOL_INTERRUPTED \
                (`Interrupted: the wait for user answers was interrupted.`). Failure details are \
                plaintext.""",
        errorsAndEdgeCases =
                """
                Use 2-5 exclusive options. Put the recommended option first; the UI adds its \
                marker. Labels must be case-insensitively unique; `Other` is reserved. Follow field \
                lengths and unique ids specified in the argument schema.""",
        security =
                "Questions are shown to the user verbatim. A user answer does not replace any separate approval required to perform an operation.",
        examples = {
            "{\"questions\":[{\"header\":\"Target\",\"id\":\"target\",\"question\":\"Which environment should receive the requested deployment?\",\"options\":[{\"label\":\"Staging\",\"description\":\"Validate the release with internal testers.\"},{\"label\":\"Production\",\"description\":\"Release to users.\"}]}]}",
            "{\"questions\":[{\"header\":\"Format\",\"id\":\"format\",\"question\":\"Which output format should the report use?\",\"options\":[{\"label\":\"Markdown\",\"description\":\"Readable in the terminal and easy to paste into documents.\"},{\"label\":\"JSON\",\"description\":\"Structured output for further tooling.\"},{\"label\":\"CSV\",\"description\":\"Tabular data for spreadsheets.\"}]}]}",
            "{\"questions\":[{\"header\":\"Scope\",\"id\":\"scope\",\"question\":\"Should the cleanup cover only src/main or also src/test?\",\"options\":[{\"label\":\"main and test\",\"description\":\"Keeps both source sets consistent.\"},{\"label\":\"main only\",\"description\":\"Limits the change to production code.\"}]},{\"header\":\"Baseline\",\"id\":\"baseline\",\"question\":\"Which branch should the comparison use as its baseline?\",\"options\":[{\"label\":\"master\",\"description\":\"Compare against the mainline branch.\"},{\"label\":\"release\",\"description\":\"Compare against the current release branch.\"}]}]}",
            "{\"questions\":[{\"header\":\"Verbosity\",\"id\":\"verbosity\",\"question\":\"Which logging level should the service use in production?\",\"options\":[{\"label\":\"WARN\",\"description\":\"Only warnings and errors; least noise.\"},{\"label\":\"INFO\",\"description\":\"Key lifecycle events plus warnings.\"},{\"label\":\"DEBUG\",\"description\":\"Detailed diagnostics; higher log volume.\"},{\"label\":\"TRACE\",\"description\":\"Finest-grained tracing; very high volume.\"},{\"label\":\"ERROR\",\"description\":\"Only hard failures.\"}]}]}",
            "{\"questions\":[{\"header\":\"Target\",\"id\":\"target\",\"question\":\"Which environment should receive the deployment?\",\"options\":[{\"label\":\"Staging\",\"description\":\"Validate with internal testers first.\"},{\"label\":\"Production\",\"description\":\"Release to users.\"}]},{\"header\":\"Budget\",\"id\":\"target\",\"question\":\"What budget applies?\",\"options\":[{\"label\":\"Standard\",\"description\":\"Default spending limits.\"},{\"label\":\"Extended\",\"description\":\"Higher limits for this release.\"}]}]}"
        },
        returnExamples = {
            "{\"answers\":{\"target\":\"Staging\"}}",
            "{\"answers\":{\"format\":\"Markdown\"}}",
            "{\"answers\":{\"scope\":\"main and test\",\"baseline\":\"master\"}}",
            "{\"answers\":{\"verbosity\":\"NOTICE\"}}",
            "Invalid questions: question ids must be unique snake_case identifiers."
        })
@ToolSecurity(capability = ToolCapability.USER_INTERACTION, defaultDanger = Danger.SAFE)
public final class AskUserTool
        implements AgentTool<AskUserTool.Args>, NativeTool<AskUserTool.Args> {

    static final int MAX_QUESTIONS = 10;
    static final int MIN_OPTIONS = 2;
    static final int MAX_OPTIONS = 5;

    private final @NonNull QuestionRuntime runtime;

    /** Creates an instance bound to the given question runtime. */
    public AskUserTool(@NonNull QuestionRuntime runtime) {
        this.runtime = runtime;
    }

    /** Model-facing arguments of {@code ask_user}. */
    public record Args(
            @ArraySize(min = 1, max = MAX_QUESTIONS)
                    @NonNull
                    @Doc(
                            "One to 10 questions shown together. Match the user's requested question count and pacing; keep sequentially requested questions in separate batches. Each object contains required `header`, `id`, `question`, and `options` fields.")
                    List<@NonNull Question> questions) {}

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
    public @NonNull String execute(@NonNull Args args) throws Exception {
        validate(args.questions());

        AnswerBatch answer;
        try {
            answer = runtime.ask(args.questions());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolExecutionException(
                    ToolResultStatus.CANCELLED,
                    ToolResultFormat.PLAINTEXT,
                    ToolErrorCode.LIFECYCLE.TOOL_INTERRUPTED,
                    "Interrupted: the wait for user answers was interrupted.");
        }
        if (answer.cancelled()) {
            throw new ToolExecutionException(
                    ToolResultStatus.CANCELLED,
                    ToolResultFormat.PLAINTEXT,
                    ToolErrorCode.LIFECYCLE.USER_CANCELLED,
                    "Cancelled: the user cancelled the question batch.");
        }
        return ToolJson.object(new Result(answer.answers()));
    }

    private static void validate(@NonNull List<@NonNull Question> questions) {
        if (questions.isEmpty() || questions.size() > MAX_QUESTIONS) {
            ToolErrors.failure(
                    ToolErrorCode.VALIDATION.INVALID_QUESTIONS,
                    "Invalid questions: ask_user requires between 1 and "
                            + MAX_QUESTIONS
                            + " questions.");
        }
        Set<String> ids = new HashSet<>();
        for (Question question : questions) {
            if (question.header().isBlank() || length(question.header()) > 12) {
                ToolErrors.failure(
                        ToolErrorCode.VALIDATION.INVALID_QUESTIONS,
                        "Invalid questions: each question header must contain 1 to 12 characters.");
            }
            if (question.id().isBlank()
                    || !question.id().matches("[a-z][a-z0-9_]*")
                    || !ids.add(question.id())) {
                ToolErrors.failure(
                        ToolErrorCode.VALIDATION.INVALID_QUESTIONS,
                        "Invalid questions: question ids must be unique snake_case identifiers.");
            }
            if (question.question().isBlank() || length(question.question()) > 300) {
                ToolErrors.failure(
                        ToolErrorCode.VALIDATION.INVALID_QUESTIONS,
                        "Invalid questions: each question prompt must contain 1 to 300"
                                + " characters.");
            }
            if (question.options().size() < MIN_OPTIONS
                    || question.options().size() > MAX_OPTIONS) {
                ToolErrors.failure(
                        ToolErrorCode.VALIDATION.INVALID_QUESTIONS,
                        "Invalid questions: question '"
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
                            ToolErrorCode.VALIDATION.INVALID_QUESTIONS,
                            "Invalid questions: question '"
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
                            ToolErrorCode.VALIDATION.INVALID_QUESTIONS,
                            "Invalid questions: option labels must be distinct, at most 120"
                                    + " characters, and not `Other`; descriptions must contain 1"
                                    + " to 200 characters.");
                }
            }
        }
    }

    private static int length(@NonNull String value) {
        return value.codePointCount(0, value.length());
    }

    /** JSON result payload of {@code ask_user}: answers keyed by question id. */
    public record Result(@NonNull Map<String, String> answers) {}
}
