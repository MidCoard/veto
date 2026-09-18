package top.focess.veto.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.screening.Danger;
import top.focess.veto.plugin.runtime.ScriptTool;

/**
 * Operator-installed script code has unknown effects and receives ordinary external-tool scrutiny.
 */
public record PluginToolDefinition(
        @NonNull String name,
        @NonNull String bindingId,
        @NonNull String pluginId,
        @NonNull String pluginVersion,
        @NonNull ScriptTool descriptor)
        implements ToolDefinition {
    @Override
    public @NonNull String description() {
        return descriptor.description();
    }

    @Override
    public @NonNull ToolCapability capability() {
        return ToolCapability.REMOTE_UNKNOWN;
    }

    @Override
    public @NonNull Danger defaultDanger() {
        return Danger.ELEVATED;
    }

    @Override
    public @NonNull JsonNode inputSchema() {
        return descriptor.inputSchema();
    }

    @Override
    public @NonNull List<@NonNull ToolResultFormat> resultFormats() {
        return List.of(ToolResultFormat.JSON);
    }
}
