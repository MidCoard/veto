package top.focess.veto.llm.core;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.NonNull;

/**
 * Thread-local store for LLM token usage. Set by client implementations after a call, and read by
 * the runner loop to calibrate the token estimation.
 */
public final class LlmSystemUsage {
    private static final @NonNull ConcurrentMap<Thread, Usage> CURRENT_USAGE =
            new ConcurrentHashMap<>();

    public record Usage(long promptTokens, long completionTokens) {}

    private LlmSystemUsage() {}

    public static void set(long prompt, long completion) {
        CURRENT_USAGE.put(Thread.currentThread(), new Usage(prompt, completion));
    }

    public static Usage getAndClear() {
        return CURRENT_USAGE.remove(Thread.currentThread());
    }
}
