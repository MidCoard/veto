package top.focess.veto.agent.mcp.tools;

import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.mcp.AgentTool;
import top.focess.veto.agent.mcp.ToolCapability;
import top.focess.veto.agent.mcp.ToolDoc;
import top.focess.veto.agent.mcp.ToolDocs;
import top.focess.veto.agent.mcp.ToolResultFormat;

/** {@code think} — a no-op call that keeps the agent loop alive for another turn. */
@Component
public final class ThinkTool implements AgentTool<ThinkTool.Args> {

    @Override
    public @NonNull String getName() {
        return "think";
    }

    @Override
    public @NonNull String getDescription() {
        ToolDoc doc =
                ToolDocs.nonNullClass(Args.class)
                        .getAnnotation(ToolDocs.nonNullClass(ToolDoc.class));
        return (doc != null && !doc.description().isEmpty()) ? doc.description() : "";
    }

    @Override
    public @NonNull Class<Args> getArgsClass() {
        return ToolDocs.nonNullClass(Args.class);
    }

    @Override
    public @NonNull ToolCapability getCapability() {
        return ToolCapability.LOOP_CONTROL;
    }

    @Override
    public @NonNull String execute(@NonNull Args args) {
        return "";
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
            security =
                    "Agent tool (`RiskCategory.AGENT`). It performs no external or persistent operation.",
            examples = {"{}"},
            returnExamples = {""})
    public record Args() {}
}
