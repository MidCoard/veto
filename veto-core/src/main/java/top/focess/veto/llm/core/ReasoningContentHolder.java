package top.focess.veto.llm.core;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.NonNull;

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

    private static final @NonNull ConcurrentMap<Thread, String> CONTENT = new ConcurrentHashMap<>();

    private ReasoningContentHolder() {}

    /** Sets the reasoning content for the current thread's most recent LLM call. */
    public static void set(String content) {
        if (content == null) {
            CONTENT.remove(Thread.currentThread());
        } else {
            CONTENT.put(Thread.currentThread(), content);
        }
    }

    /** Returns and clears the reasoning content (null if none was set). */
    public static String getAndClear() {
        return CONTENT.remove(Thread.currentThread());
    }
}
