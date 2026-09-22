package top.focess.veto.plugin.contribution;

import org.jspecify.annotations.NonNull;

/** Supplied by host bootstrap/loader, never taken from a contributed object's claimed owner. */
public record ContributionSource(
        @NonNull String namespace, @NonNull String version, @NonNull Origin origin) {
    public enum Origin {
        BUILTIN,
        PLUGIN
    }

    public ContributionSource {
        new ContributionId(namespace + ":check");
        if (version.isBlank() || version.length() > 64)
            throw new IllegalArgumentException("Invalid source version");
    }

    public @NonNull ContributionId qualify(@NonNull String localId) {
        return new ContributionId(namespace + ":" + localId);
    }
}
