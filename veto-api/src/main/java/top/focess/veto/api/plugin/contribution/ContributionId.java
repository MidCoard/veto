package top.focess.veto.api.plugin.contribution;

import org.jspecify.annotations.NonNull;

/**
 * Stable namespaced identity; neither a permission nor proof of ownership.
 *
 * @param value validated {@code namespace:local-id} value
 */
public record ContributionId(@NonNull String value) implements Comparable<ContributionId> {
    /** Validates the namespaced identity. */
    public ContributionId {
        if (value.length() > 192
                || !value.matches(
                        "[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*:[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*"))
            throw new IllegalArgumentException("Invalid contribution identity");
    }

    /**
     * Returns the local portion of this validated identity, independent of tool aliases.
     *
     * @return the portion after the namespace separator
     */
    public @NonNull String localId() {
        return value.substring(value.indexOf(':') + 1);
    }

    /**
     * Orders identities lexicographically by their full namespaced value.
     *
     * @param other identity to compare
     * @return the lexical comparison result
     */
    @Override
    public int compareTo(@NonNull ContributionId other) {
        return value.compareTo(other.value);
    }
}
