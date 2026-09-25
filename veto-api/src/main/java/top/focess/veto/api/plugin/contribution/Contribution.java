package top.focess.veto.api.plugin.contribution;

import java.util.Set;
import org.jspecify.annotations.NonNull;

/**
 * Staged implementation with a source-local ID.
 *
 * <p>The host supplies source identity and qualifies the local ID. Relative ordering is restricted
 * to this exact contribution point. Constructing this value registers no authority or lifecycle
 * admission.
 */
public record Contribution<T extends @NonNull Object>(
        @NonNull ContributionPoint<T> point,
        @NonNull String localId,
        @NonNull T implementation,
        @NonNull Set<@NonNull ContributionId> before,
        @NonNull Set<@NonNull ContributionId> after) {
    public Contribution {
        new ContributionId("local:" + localId);
        if (!point.contract().isInstance(implementation))
            throw new IllegalArgumentException("Contribution contract mismatch");
        before = Set.copyOf(before);
        after = Set.copyOf(after);
        if (before.size() + after.size() > 128)
            throw new IllegalArgumentException("Too many ordering constraints");
    }

    /** Creates a contribution without ordering constraints. */
    public static <T extends @NonNull Object> @NonNull Contribution<T> of(
            @NonNull ContributionPoint<T> point,
            @NonNull String localId,
            @NonNull T implementation) {
        return new Contribution<>(point, localId, implementation, Set.of(), Set.of());
    }
}
