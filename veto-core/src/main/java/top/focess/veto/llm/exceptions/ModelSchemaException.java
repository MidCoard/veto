package top.focess.veto.llm.exceptions;

import org.jspecify.annotations.NonNull;

/**
 * Thrown when a model response violates the {@code veto_pulse} contract after parsing — e.g.
 * effective thought ON but {@code thought} missing/empty, {@code message} missing when required,
 * both {@code calls}+{@code actionsProgram} present, or an empty turn. The loop catches it and
 * re-prompts with a formatting retry (up to N retries; then the turn fails and is surfaced to the
 * user).
 */
public class ModelSchemaException extends RuntimeException {
    public
    @NonNull
    ModelSchemaException(@NonNull String message) {
        super(message);
    }

    public
    @NonNull
    ModelSchemaException(@NonNull String message, @NonNull Throwable cause) {
        super(message, cause);
    }
}
