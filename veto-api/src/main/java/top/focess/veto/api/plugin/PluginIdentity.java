package top.focess.veto.api.plugin;

import org.jspecify.annotations.NonNull;

/**
 * Exact installed identity; version ranges and prereleases are not part of SPI v1.
 *
 * @param id stable lowercase plugin ID used for namespaces and provenance
 * @param version exact three-component semantic version
 */
public record PluginIdentity(@NonNull String id, @NonNull String version) {
    /** Validates the stable ID and exact semantic version. */
    public PluginIdentity {
        if (id.length() > 128
                || !id.matches("[a-z][a-z0-9_]*(?:[.-][a-z0-9_]+)*")
                || version.length() > 64
                || !version.matches("(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)")) {
            throw new IllegalArgumentException("Invalid plugin identity");
        }
    }
}
