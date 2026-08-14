package top.focess.veto.llm.core;

import org.jspecify.annotations.NonNull;

/**
 * Thread-local store for LLM token usage. Set by client implementations after a call, and read by
 * the runner loop to calibrate the token estimation.
 */
public final class LlmSystemUsage {
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final @NonNull ThreadLocal currentUsage = new ThreadLocal();

    public record Usage(long promptTokens, long completionTokens) {}

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void set(long prompt, long completion) {
        currentUsage.set(new Usage(prompt, completion));
    }

    public static Usage getAndClear() {
        Object value = currentUsage.get();
        Usage u = value instanceof Usage usage ? usage : null;
        currentUsage.remove();
        return u;
    }
}
