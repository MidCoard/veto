package top.focess.veto.llm.exceptions;

import org.jspecify.annotations.NonNull;

/**
 * A malformed response or a violation of the current session's response contract. The loop retries
 * with explicit formatting feedback, then fails after its retry budget.
 */
public class ModelSchemaException extends RuntimeException {
    public ModelSchemaException(@NonNull String message) {
        super(message);
    }

    public ModelSchemaException(@NonNull String message, @NonNull Throwable cause) {
        super(message, cause);
    }
}
