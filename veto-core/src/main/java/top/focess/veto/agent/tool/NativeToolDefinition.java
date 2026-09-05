package top.focess.veto.agent.tool;

import java.util.Map;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.screening.Danger;

/**
 * A native (shipped) tool. Defined by a Java class annotated with {@code @ToolSecurity}. It carries
 * both the args class (for deserialization &amp; execution) and per-parameter {@link ParamCategory}
 * hints (for the Gateway).
 */
public record NativeToolDefinition(
        @NonNull String name,
        @NonNull String description,
        @NonNull ToolCapability capability,
        @NonNull Danger defaultDanger,
        boolean requiresSemanticScreening,
        @NonNull Class<?> argsClass,
        @NonNull Map<@NonNull String, @NonNull ParamCategory> paramHints)
        implements LocalToolDefinition {
    public NativeToolDefinition {
        paramHints = Map.copyOf(paramHints);
    }
}
