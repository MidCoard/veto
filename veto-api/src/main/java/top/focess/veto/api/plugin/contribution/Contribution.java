package top.focess.veto.api.plugin.contribution;

import java.util.Set;
import org.jspecify.annotations.NonNull;

/**
 * Staged implementation with a source-local ID.
 *
 * <p>The host supplies source identity and qualifies the local ID. Relative ordering is restricted
 * to this exact contribution point. Constructing this value registers no authority or lifecycle
 * admission.
 *
 * @param <T> implementation contract type
 * @param point registration contract implemented by the value
 * @param localId source-local ID qualified by the host
 * @param implementation contributed implementation
 * @param before same-point contribution IDs that must follow this entry
 * @param after same-point contribution IDs that must precede this entry
 */
public record Contribution<T>(
        @NonNull ContributionPoint<T> point,
        @NonNull String localId,
        T implementation,
        @NonNull Set<@NonNull ContributionId> before,
        @NonNull Set<@NonNull ContributionId> after) {
    /** Validates the contract and defensively copies ordering constraints. */
    public Contribution {
        new ContributionId("local:" + localId);
        if (!point.contract().isInstance(implementation))
            throw new IllegalArgumentException("Contribution contract mismatch");
        before = Set.copyOf(before);
        after = Set.copyOf(after);
        if (before.size() + after.size() > 128)
            throw new IllegalArgumentException("Too many ordering constraints");
    }

    /**
     * Creates a contribution without ordering constraints.
     *
     * @param <T> implementation contract type
     * @param point registration contract
     * @param localId source-local ID
     * @param implementation contributed implementation
     * @return a contribution with empty ordering sets
     */
    // WHY: Checker infers a non-null type argument for this record constructor despite nullable T.
    @SuppressWarnings("argument")
    public static <T> @NonNull Contribution<T> of(
            @NonNull ContributionPoint<T> point, @NonNull String localId, T implementation) {
        return new Contribution<T>(
                point,
                localId,
                implementation,
                Set.<@NonNull ContributionId>of(),
                Set.<@NonNull ContributionId>of());
    }
}
