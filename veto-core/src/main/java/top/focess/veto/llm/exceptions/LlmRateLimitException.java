package top.focess.veto.llm.exceptions;

import org.jspecify.annotations.NonNull;

/** Provider rate-limit (HTTP 429). Retryable with backoff. */
@SuppressWarnings("serial")
public class LlmRateLimitException extends LlmException {
    /**
     * Constructs a new LlmRateLimitException with the specified message and cause.
     *
     * @param message the detail message
     * @param cause the cause of the exception
     */
    public LlmRateLimitException(@NonNull String message, Throwable cause) {
        super(message, cause, true);
    }
}
