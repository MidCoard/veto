package top.focess.veto.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * An external (user-configured) tool discovered from a registered MCP server. Carries a raw JSON
 * Schema; the Gateway applies maximum scrutiny because there are no compile-time security
 * annotations. Transcribed from {@code plans/mvp-core/part5_agent/mcp_tool_foundation.md} §6.4.
 */
public record RemoteToolDefinition(
        String name, String description, RiskCategory risk, String serverName, JsonNode inputSchema)
        implements ToolDefinition {

    @Override
    public ParameterSchema parameters() {
        return new ParameterSchema.Raw(inputSchema);
    }
}
