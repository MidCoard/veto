package top.focess.veto.plugin.runtime;

import org.jspecify.annotations.NonNull;

public record PluginBinding(
        @NonNull String id, @NonNull String version, @NonNull String revision) {}
