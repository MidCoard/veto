package top.focess.veto.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.screening.Danger;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolResultFormat;

/**
 * An out-of-process tool carrying a raw JSON Schema. The Gateway applies maximum scrutiny because
 * there are no compile-time security annotations. Two flavours share this shape, distinguished only
 * by {@link #provenance()}:
 *
 * <ul>
 *   <li>an external MCP tool discovered from a registered server ({@link #serverName()} is the
 *       server identity, provenance is null);
 *   <li>a script plugin tool executed through the plugin runtime (provenance is non-null, so the
 *       tool is session-scoped and revision-pinned).
 * </ul>
 */
public record RemoteToolDefinition(
        @NonNull String name,
        @NonNull String description,
        @NonNull String serverName,
        @NonNull ToolCapability capability,
        @NonNull Danger defaultDanger,
        @NonNull List<@NonNull ToolResultFormat> resultFormats,
        @NonNull JsonNode inputSchema,
        Provenance provenance)
        implements ToolDefinition {

    public RemoteToolDefinition {
        inputSchema = inputSchema.deepCopy();
        resultFormats = List.copyOf(resultFormats);
    }

    /** External MCP tool: unknown effect, elevated danger, JSON-or-plaintext results. */
    public RemoteToolDefinition(
            @NonNull String name,
            @NonNull String description,
            @NonNull String serverName,
            @NonNull JsonNode inputSchema) {
        this(
                name,
                description,
                serverName,
                ToolCapability.REMOTE_UNKNOWN,
                Danger.ELEVATED,
                List.of(ToolResultFormat.JSON, ToolResultFormat.PLAINTEXT),
                inputSchema,
                null);
    }

    @Override
    public @NonNull JsonNode inputSchema() {
        return inputSchema.deepCopy();
    }
}
