package top.focess.veto.llm.core;

/**
 * Thread-local holder for a provider's reasoning content (e.g. DeepSeek's {@code reasoning_content}
 * field from thinking mode). The provider client sets it after each call; {@code
 * AgentRunner.callModel} reads and clears it. The value is stored in the ASSISTANT_THOUGHT turn's
 * payload and sent back on the assistant message so the provider API accepts the conversation
 * history (DeepSeek thinking mode requires reasoning_content to be echoed back on subsequent
 * assistant messages).
 *
 * <p>Same pattern as {@link LlmSystemUsage} - a side channel for per-call metadata that doesn't fit
 * in the {@link VetoResponse} JSON schema.
 */
public final class ReasoningContentHolder {

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private ReasoningContentHolder() {}

    /** Sets the reasoning content for the current thread's most recent LLM call. */
    public static void set(@org.jspecify.annotations.Nullable String content) {
        HOLDER.set(content);
    }

    /** Returns and clears the reasoning content (null if none was set). */
    public static @org.jspecify.annotations.Nullable String getAndClear() {
        String v = HOLDER.get();
        HOLDER.remove();
        return v;
    }
}
