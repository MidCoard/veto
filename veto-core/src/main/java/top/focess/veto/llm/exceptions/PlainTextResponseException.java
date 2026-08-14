package top.focess.veto.llm.exceptions;

import org.jspecify.annotations.NonNull;

/**
 * The provider returned plain text where the veto_pulse protocol requires a JSON object (DeepSeek's
 * {@code text.format: json_schema} enforcement is probabilistic - the model sometimes answers in
 * prose). Retryable: a re-prompt usually recovers. Once the orchestrator's attempts are exhausted
 * it converts this back into a graceful plain-text {@code VetoResponse} via {@link #text()}, so a
 * persistently non-JSON answer still surfaces as the agent's message instead of an episode error.
 */
@SuppressWarnings("serial")
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

    /** The raw response text, for the graceful plain-text fallback after retries are exhausted. */
    public @NonNull String text() {
        return text;
    }
}
