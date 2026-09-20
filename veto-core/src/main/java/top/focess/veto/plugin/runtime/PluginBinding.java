package top.focess.veto.plugin.runtime;

import org.jspecify.annotations.NonNull;

public record PluginBinding(
        @NonNull String id, @NonNull String version, @NonNull String revision) {
    public PluginBinding {
        id = canonicalId(id);
    }

    /** Read compatibility for the former built-in identity; stored history is not rewritten. */
    public static @NonNull String canonicalId(@NonNull String id) {
        return id.equals("org.veto.secret-protection") ? "top.focess.secret-protection" : id;
    }
}
