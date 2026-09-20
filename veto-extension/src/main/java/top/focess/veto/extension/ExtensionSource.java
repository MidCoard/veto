package top.focess.veto.extension;

import org.jspecify.annotations.NonNull;

/** Supplied by host bootstrap/loader, never taken from a contributed object's claimed owner. */
public record ExtensionSource(
        @NonNull String namespace, @NonNull String version, @NonNull Origin origin) {
    public enum Origin {
        BUILTIN,
        PLUGIN
    }

    public ExtensionSource {
        new ExtensionId(namespace + ":check");
        if (version.isBlank() || version.length() > 64)
            throw new IllegalArgumentException("Invalid source version");
    }

    public @NonNull ExtensionId qualify(@NonNull String localId) {
        return new ExtensionId(namespace + ":" + localId);
    }
}
