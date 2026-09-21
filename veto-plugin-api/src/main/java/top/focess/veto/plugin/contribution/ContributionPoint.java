package top.focess.veto.plugin.contribution;

import org.jspecify.annotations.NonNull;

/** Host-recognized, versioned Java contract. Class identity is checked, not just class name. */
public record ContributionPoint<T extends @NonNull Object>(
        @NonNull ContributionId id,
        int major,
        @NonNull Class<T> contract,
        @NonNull Cardinality cardinality) {
    public enum Cardinality {
        SINGLE,
        MULTIPLE
    }

    public ContributionPoint {
        if (major < 1) throw new IllegalArgumentException("Invalid extension contract version");
    }
}
