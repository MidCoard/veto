package top.focess.veto.agent.mcp;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;

/**
 * An agent-internal control/meta tool — used directly inside the agent loop or workflows, not a
 * host-touching capability. Examples: {@code think} (keep the episode alive), {@code load_skill}
 * (load a skill body as an observation), {@code create_group} (spawn a delegation).
 *
 * <p>These tools carry {@link RiskCategory#AGENT} — the Gateway returns {@code NotScreened} (no
 * path/semantic screening). They still flow through the LoopInterceptor chain for audit/uniformity.
 * Parameter schemas are reflected from the args record + {@link ToolDoc} annotation.
 *
 * @param name the tool identifier (snake_case)
 * @param description the one-liner — what the tool is
 * @param argsClass the Java record carrying the tool's structured parameters
 * @param paramHints per-parameter {@link ParamCategory} hints reflected from {@link SecurityHint}
 */
public record AgentToolDefinition(
        @NonNull String name,
        @NonNull String description,
        @NonNull Class<?> argsClass,
        @NonNull Map<@NonNull String, @NonNull ParamCategory> paramHints)
        implements ToolDefinition {

    @Override
    public @NonNull RiskCategory risk() {
        return RiskCategory.AGENT;
    }

    @Override
    public @NonNull ParameterSchema parameters() {
        return new ParameterSchema.Structured(argsClass, paramHints);
    }

    @Override
    public @NonNull List<@NonNull String> examples() {
        return ToolDocs.examplesOf(argsClass);
    }

    @Override
    public @NonNull List<@NonNull String> returnExamples() {
        return ToolDocs.returnExamplesOf(argsClass);
    }

    @Override
    public @NonNull String longDescription() {
        return ToolDocs.descriptionOf(argsClass);
    }

    /**
     * Factory: reflects schema off the args record to build an {@link AgentToolDefinition}. Derives
     * the tool name from the class name, reads the one-liner {@link ToolDoc#description()} (falling
     * back to the first sentence of {@link ToolDoc#usage()}), and extracts {@link SecurityHint}
     * annotations via {@link ToolSchemaCompiler#hintsOf}.
     *
     * <p>Use {@link #from(String, Class)} when the tool name is known explicitly (e.g. from {@link
     * AgentTool#getName()}) — this avoids the class-name derivation which breaks for inner-class
     * args records whose simple name is just {@code "Args"}.
     */
    public static @NonNull AgentToolDefinition from(@NonNull Class<?> argsClass) {
        return from(ToolDocs.toolNameOf(argsClass), argsClass);
    }

    /**
     * Factory with an explicit tool name. Use this when the name is already known (e.g. from the
     * bean's {@link AgentTool#getName()}) — avoids the class-name derivation which breaks for
     * inner-class args records whose simple name is just {@code "Args"}.
     */
    public static @NonNull AgentToolDefinition from(
            @NonNull String name, @NonNull Class<?> argsClass) {
        ToolDoc doc = ToolDocs.toolDocOf(argsClass);
        String description =
                (doc != null && !doc.description().isEmpty())
                        ? doc.description()
                        : ToolDocs.firstSentenceOf(doc != null ? doc.usage() : "");
        Map<@NonNull String, @NonNull ParamCategory> hints = ToolSchemaCompiler.hintsOf(argsClass);
        return new AgentToolDefinition(name, description, argsClass, hints);
    }
}
