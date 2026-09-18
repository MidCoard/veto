package top.focess.veto.extension;

import org.jspecify.annotations.NonNull;

/** Host-recognized, versioned Java contract. Class identity is checked, not just class name. */
public record ExtensionPoint<T extends @NonNull Object>(
        @NonNull ExtensionId id,
        int major,
        @NonNull Class<T> contract,
        @NonNull Cardinality cardinality) {
    public enum Cardinality {
        SINGLE,
        MULTIPLE
    }

    public ExtensionPoint {
        if (major < 1) throw new IllegalArgumentException("Invalid extension contract version");
    }
}
