package top.focess.veto.api.util;

import java.util.ArrayDeque;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * FIFO bounded by both entry count and total queued bytes. Each entry's byte cost is captured at
 * admission and released by poll or clear; rejected offers leave the queue unchanged.
 *
 * <p>Not thread-safe. The owner must protect all access with the same lock used for its admission
 * state and wakeups. Byte costs cover queued entries, not an entry already being processed.
 *
 * @param <E> type of queued values
 */
public final class ByteBoundedQueue<E> {
    private final int maxEntries;
    private final int maxBytes;
    private final @NonNull ArrayDeque<@NonNull Entry<E>> entries = new ArrayDeque<>();
    private int queuedBytes;

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
    public boolean offer(@NonNull E value, int bytes) {
        if (bytes < 0) throw new IllegalArgumentException("Entry byte cost must not be negative");
        if (entries.size() == maxEntries || bytes > maxBytes - queuedBytes) return false;
        entries.addLast(new Entry<>(value, bytes));
        queuedBytes += bytes;
        return true;
    }

    /**
     * Removes the oldest entry and releases its captured byte cost.
     *
     * @return the oldest value, or null when empty
     */
    public @Nullable E poll() {
        var entry = entries.pollFirst();
        if (entry == null) return null;
        queuedBytes -= entry.bytes();
        return entry.value();
    }

    /**
     * Reports whether any entries are queued.
     *
     * @return whether the queue has no entries
     */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** Removes all entries and releases their byte costs. */
    public void clear() {
        entries.clear();
        queuedBytes = 0;
    }

    private record Entry<E>(@NonNull E value, int bytes) {}
}
