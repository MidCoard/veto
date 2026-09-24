package top.focess.veto.api.plugin.contribution;

import org.jspecify.annotations.NonNull;

/** Stable namespaced identity; neither a permission nor proof of ownership. */
public record ContributionId(@NonNull String value) implements Comparable<ContributionId> {
    public ContributionId {
        if (value.length() > 192
                || !value.matches(
                        "[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*:[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*"))
            throw new IllegalArgumentException("Invalid contribution identity");
    }

    /** The local portion of this validated contribution identity, independent of tool aliases. */
    public @NonNull String localId() {
        return value.substring(value.indexOf(':') + 1);
    }

    @Override
    public int compareTo(@NonNull ContributionId other) {
        return value.compareTo(other.value);
    }
}
