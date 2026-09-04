package top.focess.veto.agent.tool;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.screening.Danger;

/**
 * An agent-internal control/meta tool — used directly inside the agent loop or workflows, not a
 * host-touching capability. Examples: {@code think} (keep the episode alive), {@code load_skill}
 * (load a skill body as an observation), {@code create_group} (spawn a delegation).
 *
 * <p>The Gateway identifies this definition flavour and returns {@code NotScreened}; capability
 * still selects the caller-scoped runtime service. These tools flow through the LoopInterceptor
 * chain for audit/uniformity. Parameter schemas are reflected from the args record + {@link
 * ToolDoc} annotation.
 *
 * @param name the tool identifier (snake_case)
 * @param description the one-liner — what the tool is
 * @param argsClass the Java record carrying the tool's structured parameters
 * @param paramHints per-parameter {@link ParamCategory} hints reflected from {@link SecurityHint}
 */
public record AgentToolDefinition(
        @NonNull String name,
        @NonNull String description,
        @NonNull ToolCapability capability,
        @NonNull Danger defaultDanger,
        @NonNull Class<?> argsClass,
        @NonNull Map<@NonNull String, @NonNull ParamCategory> paramHints)
        implements ToolDefinition {

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
    public @NonNull List<@NonNull ToolResultFormat> resultFormats() {
        return ToolDocs.resultFormatsOf(argsClass);
    }

    @Override
    public @NonNull ToolDocumentation documentation() {
        return ToolDocs.documentationOf(argsClass);
    }

    /** Factory with an explicit name and effect capability supplied by the handler bean. */
    public static @NonNull AgentToolDefinition from(
            @NonNull String name, @NonNull Class<?> argsClass, @NonNull ToolCapability capability) {
        ToolDoc doc = ToolDocs.toolDocOf(argsClass);
        String description =
                (doc != null && !doc.description().isEmpty())
                        ? doc.description()
                        : ToolDocs.firstSentenceOf(doc != null ? doc.behavior() : "");
        Map<@NonNull String, @NonNull ParamCategory> hints = ToolSchemaCompiler.hintsOf(argsClass);
        return new AgentToolDefinition(
                name, description, capability, Danger.SAFE, argsClass, hints);
    }
}
