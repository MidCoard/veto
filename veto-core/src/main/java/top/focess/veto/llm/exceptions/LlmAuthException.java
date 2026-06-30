package top.focess.veto.llm.exceptions;

import org.jspecify.annotations.NonNull;

/** Authentication/authorization failure (missing or rejected credential). Never retryable. */
public class LlmAuthException extends LlmException {
    /**
     * Constructs a new LlmAuthException with the specified message.
     *
     * @param message the detail message
     */
    public
    @NonNull
    LlmAuthException(@NonNull String message) {
        super(message, false);
    }

    /**
     * Constructs a new LlmAuthException with the specified message and cause.
     *
     * @param message the detail message
     * @param cause the cause of the exception
     */
    public
    @NonNull
    LlmAuthException(@NonNull String message, @NonNull Throwable cause) {
        super(message, cause, false);
    }
}
