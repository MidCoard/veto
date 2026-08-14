package top.focess.veto.llm.exceptions;

import org.jspecify.annotations.NonNull;

/**
 * Thrown when an LLM provider or specific model does not support the required capabilities (e.g.,
 * Strict JSON Mode, Constrained Sampling), or when no provider matches the request. Permanent — not
 * retryable.
 */
@SuppressWarnings("serial")
public class ModelCapabilityException extends LlmException {
    /**
     * Constructs a new ModelCapabilityException with the specified message.
     *
     * @param message the detail message
     */
    public ModelCapabilityException(@NonNull String message) {
        super(message, false);
    }

    /**
     * Constructs a new ModelCapabilityException with the specified message and retryable flag. Use
     * {@code retryable=true} for transient issues like DeepSeek's intermittent blank-content
     * response (a known API issue with {@code response_format: json_object}).
     *
     * @param message the detail message
     * @param retryable whether the exception is retryable
     */
    public ModelCapabilityException(@NonNull String message, boolean retryable) {
        super(message, retryable);
    }

    /**
     * Constructs a new ModelCapabilityException with the specified message and cause.
     *
     * @param message the detail message
     * @param cause the cause of the exception
     */
    public ModelCapabilityException(@NonNull String message, @NonNull Throwable cause) {
        super(message, cause, false);
    }
}
