package top.focess.veto.api.plugin;

import org.jspecify.annotations.NonNull;

/** Exact installed identity; version ranges and prereleases are not part of SPI v1. */
public record PluginIdentity(@NonNull String id, @NonNull String version) {
    public PluginIdentity {
        if (id.length() > 128
                || !id.matches("[a-z][a-z0-9_]*(?:[.-][a-z0-9_]+)*")
                || version.length() > 64
                || !version.matches("(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)")) {
            throw new IllegalArgumentException("Invalid plugin identity");
        }
    }
}
