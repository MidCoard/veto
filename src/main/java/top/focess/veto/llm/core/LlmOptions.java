package top.focess.veto.llm.core;

import java.time.Duration;

/**
 * Typed sampling / transport options for an LLM call. Replaces the old untyped {@code
 * Map<String,Object> options} that every provider silently ignored.
 *
 * <p>All sampling fields are nullable — {@code null} means "use the provider default".
 *
 * @param temperature the sampling temperature
 * @param topP        the nucleus sampling top-P value
 * @param maxTokens   the maximum number of tokens to generate
 * @param timeout     the timeout duration for the call
 */
public record LlmOptions(Double temperature, Double topP, Integer maxTokens, Duration timeout) {
    private static final LlmOptions DEFAULTS =
            new LlmOptions(null, null, 4096, Duration.ofSeconds(60));

    /**
     * Returns sensible defaults: no sampling overrides, 4096 max tokens, 60s timeout.
     *
     * @return the default LLM options
     */
    public static LlmOptions defaults() {
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
    public Duration timeoutOrDefault() {
        return timeout != null ? timeout : Duration.ofSeconds(60);
    }
}
