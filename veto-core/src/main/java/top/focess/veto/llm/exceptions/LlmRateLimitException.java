package top.focess.veto.llm.exceptions;

import org.jspecify.annotations.NonNull;

/** Provider rate-limit (HTTP 429). Retryable with backoff. */
public class LlmRateLimitException extends LlmException {
    /**
     * Constructs a new LlmRateLimitException with the specified message and cause.
     *
     * @param message the detail message
     * @param cause the cause of the exception
     */
    public
    @NonNull
    LlmRateLimitException(@NonNull String message, @NonNull Throwable cause) {
        super(message, cause, true);
    }
}
