package top.focess.veto.util;

import org.jspecify.annotations.NonNull;

/** Converts nullable diagnostic values into non-null text at logging boundaries. */
public final class LogValues {

    private LogValues() {}

    public static @NonNull String safe(Object value) {
        return value == null ? "null" : value.toString();
    }
}
