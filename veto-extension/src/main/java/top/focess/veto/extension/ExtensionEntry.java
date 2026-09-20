package top.focess.veto.extension;

import org.jspecify.annotations.NonNull;

/**
 * Host-attributed catalog entry. Implementation state may be mutable; catalog structure is frozen.
 */
public record ExtensionEntry<T extends @NonNull Object>(
        @NonNull ExtensionId id, @NonNull ExtensionSource source, @NonNull T implementation) {}
