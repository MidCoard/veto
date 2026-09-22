package top.focess.veto.plugin.contribution;

import java.util.Set;
import org.jspecify.annotations.NonNull;

/** Staged implementation; relative ordering is restricted to this exact contribution point. */
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

    public static <T extends @NonNull Object> @NonNull Contribution<T> of(
            @NonNull ContributionPoint<T> point,
            @NonNull String localId,
            @NonNull T implementation) {
        return new Contribution<>(point, localId, implementation, Set.of(), Set.of());
    }
}
