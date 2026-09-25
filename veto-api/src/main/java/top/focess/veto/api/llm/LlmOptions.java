package top.focess.veto.api.llm;

import java.time.Duration;
import org.jspecify.annotations.NonNull;

/**
 * Typed sampling / transport options for an LLM call. Replaces the old untyped {@code
 * Map<String,Object> options} that every provider silently ignored.
 *
 * <p>All sampling fields are nullable — {@code null} means "use the provider default".
 *
 * @param temperature the sampling temperature
 * @param topP the nucleus sampling top-P value
 * @param maxTokens the maximum number of tokens to generate
 * @param timeout the timeout duration for the call
 * @param contextWindowTokens model context-window size, or null for the portable default
 */
public record LlmOptions(
        Double temperature,
        Double topP,
        Integer maxTokens,
        Duration timeout,
        Integer contextWindowTokens) {
    /**
     * Resolves the configured context-window size.
     *
     * @return the configured context window or the portable 128,000-token default
     */
    public int contextWindowOrDefault() {
        return contextWindowTokens != null ? contextWindowTokens : 128000;
    }

    /**
     * Reserves the configured output allowance and a ten-percent safety margin.
     *
     * @return maximum input-token budget
     * @throws IllegalStateException when the output allowance consumes the context window
     */
    public long inputBudget() {
        long available = contextWindowOrDefault() - (long) maxTokensOrDefault();
        if (available <= 0)
            throw new IllegalStateException("Output allowance exceeds context window");
        return (long) (available * 0.9);
    }

    private static final @NonNull LlmOptions DEFAULTS =
            new LlmOptions(null, null, 4096, Duration.ofSeconds(60), null);

    /**
     * Returns sensible defaults: no sampling overrides, 4096 max tokens, 60s timeout.
     *
     * @return the default LLM options
     */
    public static @NonNull LlmOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the max tokens value, or the default (4096) if null.
     *
     * @return the effective max tokens
     */
    public int maxTokensOrDefault() {
        return maxTokens != null ? maxTokens : 4096;
    }

    /**
     * Returns the timeout duration, or the default (60s) if null.
     *
     * @return the effective timeout duration
     */
    public @NonNull Duration timeoutOrDefault() {
        return timeout != null ? timeout : Duration.ofSeconds(60);
    }
}
