package top.focess.veto.api.llm;

import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * Provider adapter boundary over a third-party LLM transport. Implementations own provider-specific
 * request construction, API calls, and response parsing; the host-facing {@link LlmProvider}
 * contract does not expose SDK request or response types.
 *
 * <p>A provider plugin may implement this class to wrap its own transport and call it from its
 * {@link LlmProvider} contribution.
 */
public abstract class LlmClient {

    /** Creates a provider adapter. */
    public LlmClient() {}

    /**
     * Sends the resolved request to the LLM API and returns the raw completion text plus a
     * secret-free summary for audit logging.
     *
     * @param request the resolved request with effective URL and API key
     * @return the raw completion from the provider
     * @throws Exception if the SDK call fails
     */
    public abstract @NonNull RawCompletion complete(@NonNull ResolvedRequest request)
            throws Exception;

    /**
     * Raw provider output plus a secret-free, audit-safe summary of the request that produced it.
     *
     * @param requestSummary a non-sensitive summary of the request
     * @param rawResponse the raw response string from the provider
     * @param nativeStates opaque provider states to retain with assistant history
     * @param nativeCalls native calls decoded by the adapter
     * @param reasoning optional provider-exposed reasoning text
     */
    public record RawCompletion(
            @NonNull String requestSummary,
            @NonNull String rawResponse,
            @NonNull List<NativeToolState> nativeStates,
            @NonNull List<ToolCall> nativeCalls,
            String reasoning) {
        /** Copies native replay blocks and decoded calls. */
        public RawCompletion {
            nativeStates = List.copyOf(nativeStates);
            nativeCalls = List.copyOf(nativeCalls);
        }

        /**
         * Creates a completion without exposed reasoning text.
         *
         * @param requestSummary secret-free audit summary
         * @param rawResponse raw provider response
         * @param nativeStates opaque replay blocks
         * @param nativeCalls decoded native calls
         */
        public RawCompletion(
                @NonNull String requestSummary,
                @NonNull String rawResponse,
                @NonNull List<NativeToolState> nativeStates,
                @NonNull List<ToolCall> nativeCalls) {
            this(requestSummary, rawResponse, nativeStates, nativeCalls, null);
        }

        /**
         * Returns a copy with normalized exposed reasoning text.
         *
         * @param text provider-exposed reasoning, or {@code null} when absent
         * @return copy whose reasoning is null for blank text
         */
        public @NonNull RawCompletion withReasoning(String text) {
            return new RawCompletion(
                    requestSummary,
                    rawResponse,
                    nativeStates,
                    nativeCalls,
                    text == null || text.isBlank() ? null : text);
        }

        /**
         * Creates a plain-text completion without native blocks or calls.
         *
         * @param requestSummary secret-free audit summary
         * @param rawResponse raw provider response
         */
        public RawCompletion(@NonNull String requestSummary, @NonNull String rawResponse) {
            this(requestSummary, rawResponse, List.of(), List.of());
        }
    }
}
