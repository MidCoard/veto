package top.focess.veto.agent.tool.builtin;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.capability.UserInteractionCapability;
import top.focess.veto.agent.tool.Doc;
import top.focess.veto.agent.tool.ToolDoc;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.agent.tool.ToolResultFormat;
import top.focess.veto.agent.tool.UserInteractionTool;

/** Pauses the calling agent until the user answers one to three short questions. */
@Component
public final class AskUserTool implements UserInteractionTool<AskUserTool.Args> {

    private final @NonNull UserInteractionCapability capability;

    public AskUserTool(@NonNull UserInteractionCapability capability) {
        this.capability = capability;
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
    public @NonNull UserInteractionCapability userInteractionCapability() {
        return capability;
    }

    @Override
    public @NonNull String execute(
            @NonNull Args args, @NonNull UserInteractionCapability capability) throws Exception {
        return capability.ask(args);
    }
}
