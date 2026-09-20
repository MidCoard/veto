package top.focess.veto.extension;

import org.jspecify.annotations.NonNull;

/** Stable namespaced identity; neither a permission nor proof of ownership. */
public record ExtensionId(@NonNull String value) implements Comparable<ExtensionId> {
    public ExtensionId {
        if (value.length() > 192
                || !value.matches(
                        "[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*:[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*"))
            throw new IllegalArgumentException("Invalid extension identity");
    }

    @Override
    public int compareTo(@NonNull ExtensionId other) {
        return value.compareTo(other.value);
    }
}
