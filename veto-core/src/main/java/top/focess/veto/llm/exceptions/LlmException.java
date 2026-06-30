package top.focess.veto.llm.exceptions;

import org.jspecify.annotations.NonNull;

/**
 * Base type for all errors raised by the LLM module.
 *
 * <p>Carries a {@code retryable} flag so the orchestrator ({@code DefaultUniformLLMCaller}) can
 * decide whether a transient failure (rate-limit, timeout) is worth a backoff retry, versus a
 * permanent failure (auth, capability) that must surface immediately.
 */
public class LlmException extends RuntimeException {

    private final boolean retryable;

    /**
     * Constructs a new LlmException with the specified message and retryable flag.
     *
     * @param message the detail message
     * @param retryable whether the exception is retryable
     */
    public
    @NonNull
    LlmException(@NonNull String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    /**
     * Constructs a new LlmException with the specified message, cause, and retryable flag.
     *
     * @param message the detail message
     * @param cause the cause of the exception
     * @param retryable whether the exception is retryable
     */
    public
    @NonNull
    LlmException(@NonNull String message, @NonNull Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    /**
     * Returns whether the exception is retryable.
     *
     * @return true if retryable, false otherwise
     */
    public boolean isRetryable() {
        return retryable;
    }
}
