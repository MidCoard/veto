package top.focess.veto.api.util;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;

/** Shared worker shutdown and deadline scheduling. */
public final class Threads {
    private Threads() {}

    /**
     * Creates a single daemon timer that promptly removes cancelled deadline tasks.
     *
     * @param name timer thread name
     * @return the caller-owned scheduler
     */
    public static @NonNull ScheduledThreadPoolExecutor newDeadlineScheduler(@NonNull String name) {
        var scheduler =
                new ScheduledThreadPoolExecutor(
                        1, Thread.ofPlatform().daemon().name(name).factory());
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    /**
     * Waits for a worker to terminate, then restores any caller interruption. A zero timeout waits
     * without a deadline; a positive timeout bounds the total wait, including interruptions. Null
     * and the current thread require no wait. This method does not stop or interrupt the worker.
     *
     * @param worker worker being shut down
     * @param timeoutMillis total wait limit in milliseconds, or zero for no limit
     */
    public static void awaitTermination(Thread worker, long timeoutMillis) {
        if (timeoutMillis < 0) throw new IllegalArgumentException("Timeout must not be negative");
        if (worker == null || worker == Thread.currentThread()) return;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        boolean interrupted = false;
        while (worker.isAlive()) {
            try {
                if (timeoutMillis == 0) worker.join();
                else {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) break;
                    TimeUnit.NANOSECONDS.timedJoin(worker, remaining);
                }
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
