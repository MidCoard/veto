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
        @NonNull String name,
        @NonNull String description,
        @NonNull RiskCategory risk,
        @NonNull ToolCapability capability,
        boolean requiresSemanticScreening,
        @NonNull Class<?> argsClass,
        @NonNull Map<@NonNull String, @NonNull ParamCategory> paramHints)
        implements ToolDefinition {

    /** Compatibility constructor for callers that predate explicit capability metadata. */
    public NativeToolDefinition(
            @NonNull String name,
            @NonNull String description,
            @NonNull RiskCategory risk,
            boolean requiresSemanticScreening,
            @NonNull Class<?> argsClass,
            @NonNull Map<@NonNull String, @NonNull ParamCategory> paramHints) {
        this(
                name,
                description,
                risk,
                defaultCapability(risk),
                requiresSemanticScreening,
                argsClass,
                paramHints);
    }

    private static @NonNull ToolCapability defaultCapability(@NonNull RiskCategory risk) {
        return switch (risk) {
            case READ_ONLY -> ToolCapability.WORKSPACE_READ;
            case FILE_WRITE -> ToolCapability.WORKSPACE_WRITE;
            case SHELL_EXEC -> ToolCapability.PROCESS_EXECUTION;
            case NETWORK -> ToolCapability.NETWORK_EGRESS;
            case AGENT -> ToolCapability.AGENT_CONTROL;
        };
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
    public @NonNull List<@NonNull ToolResultFormat> resultFormats() {
        return ToolDocs.resultFormatsOf(argsClass);
    }

    @Override
    public @NonNull ToolDocumentation documentation() {
        return ToolDocs.documentationOf(argsClass);
    }
}
