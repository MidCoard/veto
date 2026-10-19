package top.focess.veto.api.util;

import java.util.ArrayDeque;
import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * Retains the most recent entries in insertion order, evicting the oldest at capacity.
 *
 * <p>Not thread-safe. Its owner supplies synchronization; snapshots are immutable and independent
 * of later additions.
 *
 * @param <E> type of retained values
 */
public final class RecentHistory<E> {
    private final int capacity;
    private final @NonNull ArrayDeque<@NonNull E> entries = new ArrayDeque<>();

    /**
     * Creates an empty history with a fixed entry limit.
     *
     * @param capacity maximum number of retained entries
     * @throws IllegalArgumentException if capacity is not positive
     */
    public RecentHistory(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("History capacity must be positive");
        this.capacity = capacity;
    }

    /**
     * Retains a value, evicting the oldest entry when full.
     *
     * @param value value to retain
     */
    public void add(@NonNull E value) {
        if (entries.size() == capacity) entries.removeFirst();
        entries.addLast(value);
    }

    /**
     * Captures the retained entries in insertion order.
     *
     * @return an immutable list ordered from oldest to newest
     */
    public @NonNull List<@NonNull E> snapshot() {
        return List.copyOf(entries);
    }
}
