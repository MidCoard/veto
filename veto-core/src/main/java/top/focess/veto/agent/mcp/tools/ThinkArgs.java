package top.focess.veto.agent.mcp.tools;

import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.mcp.AgentTool;
import top.focess.veto.agent.mcp.ToolDoc;
import top.focess.veto.agent.mcp.ToolDocs;
import top.focess.veto.agent.mcp.ToolResultFormat;

/**
 * {@code think} — no-op placeholder call. Occupies a {@code calls[]} slot so the loop continues
 * when the agent has no concrete tool to invoke but is not done.
 *
 * <p>Implements {@link AgentTool} — the record is both the args container and the tool bean. Agent
 * tools carry {@link top.focess.veto.agent.mcp.RiskCategory#AGENT}; the Gateway returns {@code
 * NotScreened}.
 */
@Component
@ToolDoc(
        resultFormats = {ToolResultFormat.PLAINTEXT},
        description =
                "No-op placeholder call. Occupies a calls[] slot so the loop continues "
                        + "when you have no concrete tool to invoke but are not done.",
        behavior =
                """
                Does nothing. Returns nothing. Its only purpose is to make `calls` non-empty so the \
                loop continues for one more turn.
                """,
        whenToUse =
                """
                Use `think` when you want another turn but have no tool to call. The loop decides \
                continue vs. stop by whether `calls` is non-empty. If you need another turn to \
                reason, plan, or compose a message - and no real tool fits - call `think` as a \
                placeholder. It keeps the episode alive.
                """,
        whenNotToUse =
                """
                - Do not call `think` when a real tool would make progress - read, search, or edit \
                instead.
                - Do not call it when you are done - omit `calls` and provide the non-blank final \
                message so the episode stops.
                - Do not call it expecting it to do anything - it is purely a placeholder.
                """,
        resultContract =
                """
                Empty.
                """,
        errorsAndEdgeCases =
                """
                A valid `{}` call has no tool-level failure. Unknown fields are rejected by the \
                shared argument validator before execution. The only normal cost is a wasted \
                round-trip - use it only when you genuinely need another turn.
                """,
        security =
                """
                Agent tool (`RiskCategory.AGENT`). The Gateway does not screen it. It touches \
                nothing. Safe to call any time.
                """,
        examples = {"{}"},
        returnExamples = {""})
public record ThinkArgs() implements AgentTool<ThinkArgs> {

    @Override
    public @NonNull String getName() {
        return "think";
    }

    @Override
    public @NonNull String getDescription() {
        ToolDoc doc =
                ToolDocs.nonNullClass(ThinkArgs.class)
                        .getAnnotation(ToolDocs.nonNullClass(ToolDoc.class));
        return (doc != null && !doc.description().isEmpty()) ? doc.description() : "";
    }

    @Override
    public @NonNull Class<ThinkArgs> getArgsClass() {
        return ToolDocs.nonNullClass(ThinkArgs.class);
    }

    @Override
    public @NonNull String execute(@NonNull ThinkArgs args) {
        return "";
    }
}
