package top.focess.veto.llm.core;

/**
 * Thread-local store for LLM token usage. Set by client implementations after a call,
 * and read by the runner loop to calibrate the token estimation.
 */
public final class LlmSystemUsage {
    private static final ThreadLocal<Usage> currentUsage = new ThreadLocal<>();

    public record Usage(long promptTokens, long completionTokens) {}

    public static void set(long prompt, long completion) {
        currentUsage.set(new Usage(prompt, completion));
    }

    public static Usage getAndClear() {
        Usage u = currentUsage.get();
        currentUsage.remove();
        return u;
    }
}
