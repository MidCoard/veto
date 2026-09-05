package top.focess.veto.agent.tool;

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
        implements LocalToolDefinition {

    public AgentToolDefinition {
        paramHints = Map.copyOf(paramHints);
    }

    /** Factory with an explicit name and effect capability supplied by the handler bean. */
    public static @NonNull AgentToolDefinition from(
            @NonNull String name, @NonNull Class<?> argsClass, @NonNull ToolCapability capability) {
        String description = ToolDocs.descriptionOf(argsClass);
        Map<@NonNull String, @NonNull ParamCategory> hints = ToolSchemaCompiler.hintsOf(argsClass);
        return new AgentToolDefinition(
                name, description, capability, Danger.SAFE, argsClass, hints);
    }
}
