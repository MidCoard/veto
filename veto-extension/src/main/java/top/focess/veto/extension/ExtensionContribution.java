package top.focess.veto.extension;

import java.util.Set;
import org.jspecify.annotations.NonNull;

/** Staged implementation; relative ordering is restricted to this exact extension point. */
public record ExtensionContribution<T extends @NonNull Object>(
        @NonNull ExtensionPoint<T> point,
        @NonNull String localId,
        @NonNull T implementation,
        @NonNull Set<@NonNull ExtensionId> before,
        @NonNull Set<@NonNull ExtensionId> after) {
    public ExtensionContribution {
        new ExtensionId("local:" + localId);
        if (!point.contract().isInstance(implementation))
            throw new IllegalArgumentException("Extension contract mismatch");
        before = Set.copyOf(before);
        after = Set.copyOf(after);
        if (before.size() + after.size() > 128)
            throw new IllegalArgumentException("Too many ordering constraints");
    }

    public static <T extends @NonNull Object> @NonNull ExtensionContribution<T> of(
            @NonNull ExtensionPoint<T> point, @NonNull String localId, @NonNull T implementation) {
        return new ExtensionContribution<>(point, localId, implementation, Set.of(), Set.of());
    }
}
