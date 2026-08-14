package top.focess.veto.llm.exceptions;

import org.jspecify.annotations.NonNull;

/**
 * Thrown when a model response violates the {@code veto_pulse} contract after parsing — e.g. {@code
 * features} missing, {@code message} missing when stopping (no calls and no actions), or both
 * {@code calls}+{@code actions} present. The loop catches it and re-prompts with a formatting retry
 * (up to N retries; then the turn fails and is surfaced to the user).
 */
@SuppressWarnings("serial")
public class ModelSchemaException extends RuntimeException {
    public ModelSchemaException(@NonNull String message) {
        super(message);
    }

    public ModelSchemaException(@NonNull String message, @NonNull Throwable cause) {
        super(message, cause);
    }
}
