package top.focess.veto.llm.exceptions;

/**
 * Authentication/authorization failure (missing or rejected credential). Never retryable.
 */
public class LlmAuthException extends LlmException {
    /**
     * Constructs a new LlmAuthException with the specified message.
     *
     * @param message the detail message
     */
    public LlmAuthException(String message) {
        super(message, false);
    }

    /**
     * Constructs a new LlmAuthException with the specified message and cause.
     *
     * @param message the detail message
     * @param cause the cause of the exception
     */
    public LlmAuthException(String message, Throwable cause) {
        super(message, cause, false);
    }
}
