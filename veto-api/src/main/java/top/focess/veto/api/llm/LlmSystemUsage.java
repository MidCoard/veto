package top.focess.veto.api.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.NonNull;

/** Provider measurements retained for every completed attempt, including parser retries. */
public final class LlmSystemUsage {
    private static final @NonNull ConcurrentMap<Thread, List<Usage>> CURRENT_USAGE =
            new ConcurrentHashMap<>();

    private static final @NonNull Set<Thread> CAPTURING = ConcurrentHashMap.newKeySet();

    /** Starts collecting provider usage on the current thread, discarding stale measurements. */
    public static void begin() {
        drain();
        CAPTURING.add(Thread.currentThread());
    }

    /**
     * Token usage reported for one completed provider attempt.
     *
     * @param promptTokens charged input tokens
     * @param completionTokens generated output tokens
     * @param cacheReadInputTokens optional input tokens read from cache
     * @param cacheCreationInputTokens optional input tokens written to cache
     */
    public record Usage(
            long promptTokens,
            long completionTokens,
            Long cacheReadInputTokens,
            Long cacheCreationInputTokens) {
        /**
         * Creates usage without cache measurements.
         *
         * @param promptTokens charged input tokens
         * @param completionTokens generated output tokens
         */
        public Usage(long promptTokens, long completionTokens) {
            this(promptTokens, completionTokens, null, null);
        }

        /** Discards inconsistent cache measurements while retaining total usage. */
        public Usage {
            if (cacheReadInputTokens != null
                    && (cacheReadInputTokens < 0 || cacheReadInputTokens > promptTokens))
                cacheReadInputTokens = null;
            if (cacheCreationInputTokens != null
                    && (cacheCreationInputTokens < 0 || cacheCreationInputTokens > promptTokens))
                cacheCreationInputTokens = null;
            if (cacheReadInputTokens != null
                    && cacheCreationInputTokens != null
                    && cacheReadInputTokens > promptTokens - cacheCreationInputTokens) {
                cacheReadInputTokens = null;
                cacheCreationInputTokens = null;
            }
        }
    }

    private LlmSystemUsage() {}

    /**
     * Records one attempt without cache measurements.
     *
     * @param prompt charged input tokens
     * @param completion generated output tokens
     */
    public static void set(long prompt, long completion) {
        set(prompt, completion, null, null);
    }

    /**
     * Records one completed attempt for the current thread; negative totals are ignored.
     *
     * @param prompt charged input tokens
     * @param completion generated output tokens
     * @param cacheRead optional cached input tokens
     * @param cacheCreation optional cache creation input tokens
     */
    public static void set(long prompt, long completion, Long cacheRead, Long cacheCreation) {
        if (prompt < 0 || completion < 0) return;
        if (!CAPTURING.contains(Thread.currentThread()))
            CURRENT_USAGE.remove(Thread.currentThread());
        Thread thread = Thread.currentThread();
        List<Usage> values = CURRENT_USAGE.get(thread);
        if (values == null) {
            values = new ArrayList<Usage>();
            CURRENT_USAGE.put(thread, values);
        }
        values.add(new Usage(prompt, completion, cacheRead, cacheCreation));
    }

    /**
     * Ends capture and removes the current thread's measurements.
     *
     * @return immutable measurements in recording order
     */
    public static @NonNull List<Usage> drain() {
        CAPTURING.remove(Thread.currentThread());
        List<Usage> values = CURRENT_USAGE.remove(Thread.currentThread());
        return values == null ? List.of() : List.copyOf(values);
    }

    /**
     * Observes current-thread usage without consuming the runner's accounting data.
     *
     * @return immutable snapshot of recorded attempts
     */
    public static @NonNull List<Usage> snapshot() {
        List<Usage> values = CURRENT_USAGE.get(Thread.currentThread());
        return values == null ? List.of() : List.copyOf(values);
    }

    /**
     * Ends capture and returns the last recorded attempt.
     *
     * @return latest attempt, or {@code null} if none was recorded
     */
    public static Usage getAndClear() {
        List<Usage> values = drain();
        return values.isEmpty() ? null : values.getLast();
    }
}
