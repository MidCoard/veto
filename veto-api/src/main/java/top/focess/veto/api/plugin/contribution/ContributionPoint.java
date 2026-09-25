package top.focess.veto.api.plugin.contribution;

import org.jspecify.annotations.NonNull;

/**
 * Host-recognized, major-versioned Java registration contract.
 *
 * <p>Class identity is checked, not just class name. A point describes validation and cardinality;
 * it does not grant implementations permission to perform host effects.
 *
 * @param <T> implementation contract type
 * @param id stable contribution-point identity
 * @param major positive major contract version
 * @param contract exact Java implementation contract
 * @param cardinality allowed number of implementations
 */
public record ContributionPoint<T extends @NonNull Object>(
        @NonNull ContributionId id,
        int major,
        @NonNull Class<T> contract,
        @NonNull Cardinality cardinality) {
    /** Supported contribution counts for a point. */
    public enum Cardinality {
        /** At most one implementation may be published. */
        SINGLE,
        /** Any validated number of implementations may be published. */
        MULTIPLE
    }

    /** Validates the contribution contract version. */
    public ContributionPoint {
        if (major < 1) throw new IllegalArgumentException("Invalid contribution contract version");
    }
}
