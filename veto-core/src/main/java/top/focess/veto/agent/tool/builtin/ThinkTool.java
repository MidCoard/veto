package top.focess.veto.agent.tool.builtin;

import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.capability.LoopControlCapability;
import top.focess.veto.agent.tool.LoopControlTool;
import top.focess.veto.agent.tool.ToolDoc;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.agent.tool.ToolResultFormat;

/** {@code think} — a no-op call that keeps the agent loop alive for another turn. */
@Component
public final class ThinkTool implements LoopControlTool<ThinkTool.Args> {

    private final @NonNull LoopControlCapability capability;

    public ThinkTool(@NonNull LoopControlCapability capability) {
        this.capability = capability;
    }

    @Override
    public @NonNull String getName() {
        return "think";
    }

    @Override
    public @NonNull Class<Args> getArgsClass() {
        return ToolDocs.nonNullClass(Args.class);
    }

    @Override
    public @NonNull LoopControlCapability loopControlCapability() {
        return capability;
    }

    @Override
    public @NonNull String execute(@NonNull Args args, @NonNull LoopControlCapability capability)
            throws Exception {
        return capability.continueLoop(args);
    }

    @ToolDoc(
            resultFormats = {ToolResultFormat.PLAINTEXT},
            description =
                    "No-op placeholder call. Keeps the loop active when another reasoning turn is needed.",
            behavior = "Does nothing and returns empty text.",
            whenToUse =
                    "Use `think` only when another turn is needed and no real tool call can make progress.",
            whenNotToUse =
                    """
                    - Do not call `think` when a real tool can make progress.
                    - Do not call it when the task is complete.
                    """,
            resultContract = "Successful empty plain text.",
            errorsAndEdgeCases =
                    "Unknown fields are rejected by the shared argument validator before execution.",
            security = "Performs no external or persistent operation.",
            examples = {"{}"},
            returnExamples = {""})
    public record Args() {}
}
