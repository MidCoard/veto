package top.focess.veto.agent.tool;

import org.jspecify.annotations.NonNull;

/**
 * A tool definition whose execution is owned by an installed plugin, so it carries plugin
 * provenance independent of how it executes. Both plugin flavours are session-scoped: a session
 * only sees the plugins pinned at its creation, and a permit binds the exact plugin revision.
 *
 * <ul>
 *   <li>{@link PluginToolDefinition} — an out-of-process script tool executed through the plugin
 *       runtime against a JSON schema.
 *   <li>{@link PluginNativeToolDefinition} — an in-process JAR tool executed through the internal
 *       tool state against a typed argument record.
 * </ul>
 */
public interface PluginSourced {
    /** The pinned plugin binding identity a permit must match. */
    @NonNull String bindingId();

    /** The plugin namespace whose session selection gates this tool's visibility. */
    @NonNull String pluginId();

    /** The pinned plugin revision a session binding must match. */
    @NonNull String pluginVersion();
}
