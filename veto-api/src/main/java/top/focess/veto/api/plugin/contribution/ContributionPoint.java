package top.focess.veto.api.plugin.contribution;

import org.jspecify.annotations.NonNull;

/**
 * Host-recognized, major-versioned Java registration contract.
 *
 * <p>Class identity is checked, not just class name. A point describes validation and cardinality;
 * it does not grant implementations permission to perform host effects.
 */
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
        if (major < 1) throw new IllegalArgumentException("Invalid contribution contract version");
    }
}
