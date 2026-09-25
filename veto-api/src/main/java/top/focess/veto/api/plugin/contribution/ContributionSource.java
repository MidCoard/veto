package top.focess.veto.api.plugin.contribution;

import org.jspecify.annotations.NonNull;

/**
 * Source supplied by host bootstrap or loader, never by a contributed object's claimed owner.
 *
 * @param namespace validated contribution namespace
 * @param version source version reported by the loader
 * @param origin whether the source is distribution-bundled or externally installed
 */
public record ContributionSource(
        @NonNull String namespace, @NonNull String version, @NonNull Origin origin) {
    /** Loader-attributed source origin. */
    public enum Origin {
        /** Distribution-bundled source. */
        BUILTIN,
        /** Externally installed plugin source. */
        PLUGIN
    }

    /** Validates the source namespace and version. */
    public ContributionSource {
        new ContributionId(namespace + ":check");
        if (version.isBlank() || version.length() > 64)
            throw new IllegalArgumentException("Invalid source version");
    }

    /**
     * Returns the validated namespaced ID for {@code localId}.
     *
     * @param localId source-local contribution ID
     * @return the qualified contribution ID
     */
    public @NonNull ContributionId qualify(@NonNull String localId) {
        return new ContributionId(namespace + ":" + localId);
    }
}
