package top.focess.veto.agent.mcp;

import java.util.Map;

/**
 * An engine-provided control/meta tool — used directly inside the agent loop or workflows, not a
 * host-touching capability. Examples: {@code create_group} (spawn a delegation), {@code load_skill}
 * (load a skill body as an observation). Transcribed from {@code
 * plans/mvp-core/part5_agent/mcp_tool_foundation.md} §6.4.
 *
 * <p>These tools are <b>always available</b> to every agent (the user does not whitelist them) and
 * carry no host-path parameters, so the Gateway applies no path/semantic screening. They still flow
 * through the LoopInterceptor chain for audit/uniformity. Parameter schemas are engine-defined
 * (declared in code, not reflected from a user record), so they use {@link
 * ParameterSchema.Structured} with an empty hints map.
 */
public record AgentToolDefinition(
        String name, String description, Class<?> argsClass, Map<String, ParamCategory> paramHints)
        implements ToolDefinition {

    @Override
    public RiskCategory risk() {
        return RiskCategory.AGENT;
    }

    @Override
    public ParameterSchema parameters() {
        return new ParameterSchema.Structured(argsClass, paramHints);
    }
}
