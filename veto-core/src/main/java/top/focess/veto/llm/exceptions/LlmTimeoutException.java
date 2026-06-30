package top.focess.veto.llm.exceptions;

import org.jspecify.annotations.NonNull;

/** Network/read timeout talking to the provider. Retryable with backoff. */
public class LlmTimeoutException extends LlmException {
    /**
     * Constructs a new LlmTimeoutException with the specified message and cause.
     *
     * @param message the detail message
     * @param cause the cause of the exception
     */
    public
    @NonNull
    LlmTimeoutException(@NonNull String message, @NonNull Throwable cause) {
        super(message, cause, true);
    }
}
