package top.focess.veto.api.plugin;

import org.jspecify.annotations.NonNull;

/**
 * Immutable selection of one installed plugin artifact for a session.
 *
 * @param id exact stable plugin identity
 * @param version declared plugin version selected for the session
 * @param revision installed artifact revision used to resolve that selection
 */
public record PluginBinding(
        @NonNull String id, @NonNull String version, @NonNull String revision) {}
