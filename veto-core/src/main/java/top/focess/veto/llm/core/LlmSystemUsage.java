package top.focess.veto.llm.core;

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
    public static void begin() { drain(); CAPTURING.add(Thread.currentThread()); }

    public record Usage(long promptTokens, long completionTokens) {}

    private LlmSystemUsage() {}

    public static void set(long prompt, long completion) {
        if (prompt < 0 || completion < 0) return;
        if (!CAPTURING.contains(Thread.currentThread())) CURRENT_USAGE.remove(Thread.currentThread());
        CURRENT_USAGE
                .computeIfAbsent(Thread.currentThread(), ignored -> new ArrayList<>())
                .add(new Usage(prompt, completion));
    }

    public static @NonNull List<Usage> drain() {
        CAPTURING.remove(Thread.currentThread());
        List<Usage> values = CURRENT_USAGE.remove(Thread.currentThread());
        return values == null ? List.of() : List.copyOf(values);
    }

    public static Usage getAndClear() {
        List<Usage> values = drain();
        return values.isEmpty() ? null : values.getLast();
    }
}
