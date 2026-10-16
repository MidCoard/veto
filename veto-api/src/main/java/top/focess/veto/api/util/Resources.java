package top.focess.veto.api.util;

import org.jspecify.annotations.NonNull;

/** Cleanup for resources whose ownership cannot be expressed with try-with-resources. */
public final class Resources {
    private Resources() {}

    /**
     * Closes an owned resource, preserving the primary failure and any distinct cleanup failure.
     *
     * @param resource resource owned by the failed operation
     * @param failure original failure to preserve
     */
    public static void closeOnFailure(@NonNull AutoCloseable resource, @NonNull Throwable failure) {
        try {
            resource.close();
        } catch (Throwable cleanup) {
            if (cleanup != failure) failure.addSuppressed(cleanup);
        }
    }
}
