package top.focess.veto.llm.exceptions;

import org.jspecify.annotations.NonNull;

/**
 * The provider returned plain text where the veto_pulse protocol requires a JSON object (DeepSeek's
 * {@code text.format: json_schema} enforcement is probabilistic - the model sometimes answers in
 * prose). The orchestrator routes this to the runner's bounded schema correction path instead of
 * repeating an unchanged request or accepting an unstructured answer as success.
 */
public class PlainTextResponseException extends LlmException {

    private final @NonNull String text;

    /**
     * Constructs a new PlainTextResponseException for the given provider and raw text.
     *
     * @param providerName the provider that returned plain text
     * @param text the raw (stripped) response text
     */
    public PlainTextResponseException(@NonNull String providerName, @NonNull String text) {
        super(providerName + " response was plain text, not veto_pulse JSON", true);
        this.text = text;
    }

    /** The rejected raw response text, retained for diagnostics. */
    public @NonNull String text() {
        return text;
    }
}
