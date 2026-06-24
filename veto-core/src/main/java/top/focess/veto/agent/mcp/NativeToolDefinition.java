package top.focess.veto.agent.mcp;

import java.util.Map;

/**
 * A native (shipped) tool. Defined by a Java class annotated with {@code @ToolSecurity}. {@link
 * ParameterSchema.Structured} carries both the args class (for deserialization &amp; execution) and
 * per-parameter {@link ParamCategory} hints (for the Gateway). {@code
 * plans/mvp-core/part5_agent/mcp_tool_foundation.md}.
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
    public ParameterSchema parameters() {
        return new ParameterSchema.Structured(argsClass, paramHints);
    }
}
