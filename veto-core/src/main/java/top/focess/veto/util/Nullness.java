package top.focess.veto.util;

import org.jspecify.annotations.NonNull;

/** Explicitly narrows a nullable-by-default value after a runtime null check. */
public final class Nullness {

    private Nullness() {}

    public static <T> @NonNull T requireNonNull(T value) {
        return requireNonNull(value, "Required value was null");
    }

    public static <T> @NonNull T requireNonNull(T value, @NonNull String message) {
        if (value == null) {
            throw new IllegalStateException(message);
        }
        return value;
    }
}
