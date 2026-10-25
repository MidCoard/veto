package top.focess.veto.api.util;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * FIFO bounded by both entry count and total queued bytes. Each entry's byte cost is captured at
 * admission and released by poll or clear; rejected offers leave the queue unchanged.
 *
 * <p>Thread-safe. Timed producers wait for capacity; polling and closure wake them. Byte costs
 * cover queued entries, not an entry already being processed. Closure can retain accepted entries
 * for draining, but always rejects new entries.
 *
 * @param <E> type of queued values
 */
public final class ByteBoundedQueue<E> {
    private final int maxEntries;
    private final int maxBytes;
    private final @NonNull ArrayDeque<@NonNull Entry<E>> entries = new ArrayDeque<>();
    private int queuedBytes;
    private boolean closed;

    /**
     * Creates an empty queue with fixed limits.
     *
     * @param maxEntries maximum number of queued entries
     * @param maxBytes maximum total byte cost of queued entries
     * @throws IllegalArgumentException if either limit is not positive
     */
    public ByteBoundedQueue(int maxEntries, int maxBytes) {
        if (maxEntries <= 0 || maxBytes <= 0)
            throw new IllegalArgumentException("Queue limits must be positive");
        this.maxEntries = maxEntries;
        this.maxBytes = maxBytes;
    }

    /**
     * Returns false when either limit would be exceeded; zero-byte entries still consume a slot.
     *
     * @param value value to enqueue
     * @param bytes immutable byte cost captured for this entry
     * @return whether the entry was admitted
     * @throws IllegalArgumentException if the byte cost is negative
     */
    public synchronized boolean offer(@NonNull E value, int bytes) {
        if (bytes < 0) throw new IllegalArgumentException("Entry byte cost must not be negative");
        if (closed || entries.size() == maxEntries || bytes > maxBytes - queuedBytes) return false;
        entries.addLast(new Entry<>(value, bytes));
        queuedBytes += bytes;
        notifyAll();
        return true;
    }

    /** Waits for capacity; returns false on closure, timeout or an entry larger than the budget. */
    public synchronized boolean offer(
            @NonNull E value, int bytes, long timeout, @NonNull TimeUnit unit)
            throws InterruptedException {
        if (bytes < 0) throw new IllegalArgumentException("Entry byte cost must not be negative");
        if (bytes > maxBytes) return false;
        long remaining = unit.toNanos(timeout);
        long deadline = System.nanoTime() + remaining;
        while (!closed) {
            if (offer(value, bytes)) return true;
            if (remaining <= 0) return false;
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
            remaining = deadline - System.nanoTime();
        }
        return false;
    }

    /**
     * Removes the oldest entry and releases its captured byte cost.
     *
     * @return the oldest value, or null when empty
     */
    public synchronized @Nullable E poll() {
        var entry = entries.pollFirst();
        if (entry == null) return null;
        queuedBytes -= entry.bytes();
        notifyAll();
        return entry.value();
    }

    /** Waits for an entry; returns null on timeout or once the closed queue has drained. */
    public synchronized @Nullable E poll(long timeout, @NonNull TimeUnit unit)
            throws InterruptedException {
        long remaining = unit.toNanos(timeout);
        long deadline = System.nanoTime() + remaining;
        while (entries.isEmpty() && !closed && remaining > 0) {
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
            remaining = deadline - System.nanoTime();
        }
        return poll();
    }

    /** Waits for an entry; returns null only after closure and draining. */
    public synchronized @Nullable E take() throws InterruptedException {
        while (entries.isEmpty() && !closed) wait();
        return poll();
    }

    /**
     * Reports whether any entries are queued.
     *
     * @return whether the queue has no entries
     */
    public synchronized boolean isEmpty() {
        return entries.isEmpty();
    }

    /** Removes all entries and releases their byte costs. */
    public synchronized void clear() {
        entries.clear();
        queuedBytes = 0;
        notifyAll();
    }

    /** Ends admission and wakes all waiters; immediate closure also discards queued entries. */
    public synchronized void close(boolean drain) {
        closed = true;
        if (!drain) clear();
        notifyAll();
    }

    private record Entry<E>(@NonNull E value, int bytes) {}
}
