package top.focess.veto.api.util;

import org.jspecify.annotations.NonNull;

/** Waits for owned workers. */
public final class Threads {
    private Threads() {}

    /**
     * Joins owned workers in order. Interruption ends the wait without stopping the workers; the
     * caller decides whether to continue cleanup. Workers must be distinct from the calling thread.
     *
     * @param workers owned workers whose shutdown has already been signalled
     * @throws InterruptedException if the caller is interrupted while waiting
     */
    public static void joinAll(@NonNull Iterable<@NonNull Thread> workers)
            throws InterruptedException {
        for (var worker : workers) worker.join();
    }

    /**
     * Waits for a worker to terminate. A zero timeout waits without a deadline. Null and the
     * current thread require no wait. Interruption ends the wait; it does not stop the worker.
     *
     * @param worker worker being shut down
     * @param timeoutMillis total wait limit in milliseconds, or zero for no limit
     * @throws InterruptedException if the caller is interrupted while waiting
     */
    public static void awaitTermination(Thread worker, long timeoutMillis)
            throws InterruptedException {
        if (timeoutMillis < 0) throw new IllegalArgumentException("Timeout must not be negative");
        if (worker == null || worker == Thread.currentThread()) return;
        worker.join(timeoutMillis);
    }
}
