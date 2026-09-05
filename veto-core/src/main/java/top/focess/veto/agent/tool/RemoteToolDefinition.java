package top.focess.veto.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.screening.Danger;

/**
 * An external (user-configured) tool discovered from a registered MCP server. Carries a raw JSON
 * Schema; the Gateway applies maximum scrutiny because there are no compile-time security
 * annotations.
 */
public record RemoteToolDefinition(
        @NonNull String name,
        @NonNull String description,
        @NonNull String serverName,
        @NonNull JsonNode inputSchema)
        implements ToolDefinition {

    public RemoteToolDefinition {
        inputSchema = inputSchema.deepCopy();
    }

    @Override
    public @NonNull JsonNode inputSchema() {
        return inputSchema.deepCopy();
    }

    @Override
    public @NonNull ToolCapability capability() {
        return ToolCapability.REMOTE_UNKNOWN;
    }

    @Override
    public @NonNull Danger defaultDanger() {
        return Danger.ELEVATED;
    }
}
