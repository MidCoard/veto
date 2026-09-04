package top.focess.veto.agent.mcp;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.screening.Danger;

/**
 * A native (shipped) tool. Defined by a Java class annotated with {@code @ToolSecurity}. {@link
 * ParameterSchema.Structured} carries both the args class (for deserialization &amp; execution) and
 * per-parameter {@link ParamCategory} hints (for the Gateway).
 */
public record NativeToolDefinition(
        @NonNull String name,
        @NonNull String description,
        @NonNull ToolCapability capability,
        @NonNull Danger defaultDanger,
        boolean requiresSemanticScreening,
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
}
