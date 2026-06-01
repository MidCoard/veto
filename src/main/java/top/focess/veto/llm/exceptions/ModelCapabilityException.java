package top.focess.veto.llm.exceptions;

/**
 * Thrown when an LLM provider or specific model does not support the required capabilities (e.g.,
 * Strict JSON Mode, Constrained Sampling), or when no provider matches the request. Permanent — not
 * retryable.
 */
public class ModelCapabilityException extends LlmException {
    /**
     * Constructs a new ModelCapabilityException with the specified message.
     *
     * @param message the detail message
     */
    public ModelCapabilityException(String message) {
        super(message, false);
    }

    /**
     * Constructs a new ModelCapabilityException with the specified message and cause.
     *
     * @param message the detail message
     * @param cause   the cause of the exception
     */
    public ModelCapabilityException(String message, Throwable cause) {
        super(message, cause, false);
    }
}
