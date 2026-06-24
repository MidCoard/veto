package top.focess.veto.llm.exceptions;

/**
 * Thrown when a model response violates the {@code veto_pulse} contract after parsing (LLD {@code
 * prompt_react_syntax.md} §2.1.1) — e.g. effective thought ON but {@code thought} missing/empty,
 * {@code message} missing when required, both {@code calls}+{@code actionsProgram} present, or an
 * empty turn. The loop catches it and re-prompts with a formatting retry (up to N retries; then the
 * turn fails and is surfaced to the user).
 */
public class ModelSchemaException extends RuntimeException {
    public ModelSchemaException(String message) {
        super(message);
    }

    public ModelSchemaException(String message, Throwable cause) {
        super(message, cause);
    }
}
