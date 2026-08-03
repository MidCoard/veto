package top.focess.veto.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.jspecify.annotations.NonNull;

/**
 * An external (user-configured) tool discovered from a registered MCP server. Carries a raw JSON
 * Schema; the Gateway applies maximum scrutiny because there are no compile-time security
 * annotations..
 */
public record RemoteToolDefinition(
        @NonNull String name,
        @NonNull String description,
        @NonNull RiskCategory risk,
        @NonNull String serverName,
        @NonNull JsonNode inputSchema)
        implements ToolDefinition {

    @Override
    public @NonNull ParameterSchema parameters() {
        return new ParameterSchema.Raw(inputSchema);
    }
}
