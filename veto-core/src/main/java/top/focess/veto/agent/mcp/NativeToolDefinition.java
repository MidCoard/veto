package top.focess.veto.agent.mcp;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;

/**
 * A native (shipped) tool. Defined by a Java class annotated with {@code @ToolSecurity}. {@link
 * ParameterSchema.Structured} carries both the args class (for deserialization &amp; execution) and
 * per-parameter {@link ParamCategory} hints (for the Gateway).
 */
public record NativeToolDefinition(
        String name,
        String description,
        RiskCategory risk,
        boolean requiresSemanticScreening,
        Class<?> argsClass,
        Map<String, ParamCategory> paramHints)
        implements ToolDefinition {

    @Override
    public @NonNull ParameterSchema parameters() {
        return new ParameterSchema.Structured(argsClass, paramHints);
    }

    @Override
    public @NonNull List<String> examples() {
        return ToolDocs.examplesOf(argsClass);
    }

    @Override
    public @NonNull String longDescription() {
        return ToolDocs.descriptionOf(argsClass);
    }
}
