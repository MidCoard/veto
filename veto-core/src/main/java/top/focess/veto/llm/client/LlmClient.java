package top.focess.veto.llm.client;

import top.focess.veto.llm.core.ResolvedRequest;

/**
 * Our abstraction over third-party LLM SDK clients. Encapsulates all provider-specific request
 * building, API calling, and response parsing — so that no SDK types ever leak into provider code.
 *
 * <p>Implementations are SDK-specific adapters (e.g. {@code OpenAiLlmClient}). Providers receive an
 * {@code LlmClient} from {@link LlmClientFactory} and call {@link #complete(ResolvedRequest)} —
 * they never see the underlying SDK client.
 *
 * <p>Plugin providers extend this class to wrap their own SDKs.
 */
public abstract class LlmClient {

    /**
     * Sends the resolved request to the LLM API and returns the raw completion text plus a
     * secret-free summary for audit logging.
     *
     * @param request the resolved request with effective URL and API key
     * @return the raw completion from the provider
     * @throws Exception if the SDK call fails
     */
    public abstract RawCompletion complete(ResolvedRequest request) throws Exception;

    /**
     * Raw provider output plus a secret-free, audit-safe summary of the request that produced it.
     *
     * @param requestSummary a non-sensitive summary of the request
     * @param rawResponse the raw response string from the provider
     */
    public record RawCompletion(String requestSummary, String rawResponse) {
    }
}
