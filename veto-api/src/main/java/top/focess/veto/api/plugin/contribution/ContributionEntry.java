package top.focess.veto.api.plugin.contribution;

import org.jspecify.annotations.NonNull;

/**
 * Host-attributed catalog entry. Implementation state may be mutable; catalog structure is frozen.
 *
 * @param <T> implementation contract type
 * @param id host-qualified contribution identity
 * @param source loader-attributed contribution source
 * @param implementation registered implementation
 */
public record ContributionEntry<T extends @NonNull Object>(
        @NonNull ContributionId id, @NonNull ContributionSource source, T implementation) {}
