package top.focess.veto.api.llm.exceptions;

import org.jspecify.annotations.NonNull;

/**
 * A malformed response or a violation of the current session's response contract. The loop retries
 * with explicit formatting feedback, then fails after its retry budget.
 */
public class ModelSchemaException extends RuntimeException {
    /**
     * Creates a schema failure with a corrective diagnostic.
     *
     * @param message response-contract violation diagnostic
     */
    public ModelSchemaException(@NonNull String message) {
        super(message);
    }

    /**
     * Creates a schema failure caused by decoding or validation.
     *
     * @param message response-contract violation diagnostic
     * @param cause underlying decoding or validation failure
     */
    public ModelSchemaException(@NonNull String message, @NonNull Throwable cause) {
        super(message, cause);
    }
}
