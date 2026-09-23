package top.focess.veto.api.plugin.contribution;

import org.jspecify.annotations.NonNull;

/**
 * Host-attributed catalog entry. Implementation state may be mutable; catalog structure is frozen.
 */
public record ContributionEntry<T extends @NonNull Object>(
        @NonNull ContributionId id,
        @NonNull ContributionSource source,
        @NonNull T implementation) {}
