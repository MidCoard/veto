package top.focess.veto.llm.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import top.focess.veto.llm.client.LlmClient;
import top.focess.veto.llm.core.ResolvedRequest;
import top.focess.veto.llm.core.VetoResponse;
import top.focess.veto.llm.exceptions.LlmAuthException;
import top.focess.veto.llm.exceptions.LlmException;
import top.focess.veto.llm.exceptions.LlmRateLimitException;
import top.focess.veto.llm.exceptions.LlmTimeoutException;
import top.focess.veto.llm.exceptions.ModelCapabilityException;
import top.focess.veto.observability.AuditLogger;

/**
 * Template that owns the cross-cutting concerns every provider shares: audit logging, response
 * parsing, and SDK-exception classification into the typed {@link LlmException} hierarchy.
 *
 * <p>Subclasses only implement {@link #invoke}, the thin provider-specific SDK call. SDK-specific
 * logic is confined to {@link LlmClient} adapters — providers never import SDK types.
 */
public abstract class AbstractLlmProvider implements LLMProviderStrategy {
    protected final ObjectMapper objectMapper;
    protected final AuditLogger auditLogger;

    /**
     * Constructs a new AbstractLlmProvider with the specified dependencies.
     *
     * @param objectMapper the mapper for JSON serialization
     * @param auditLogger the logger for auditing requests
     */
    protected AbstractLlmProvider(ObjectMapper objectMapper, AuditLogger auditLogger) {
        this.objectMapper = objectMapper;
        this.auditLogger = auditLogger;
    }

    /**
     * Provider-specific SDK call. Returns the raw model output plus a secret-free request summary.
     *
     * @param request the resolved request containing the effective URL and API key
     * @return the raw completion from the provider
     * @throws Exception if the SDK call fails
     */
    protected abstract LlmClient.RawCompletion invoke(ResolvedRequest request) throws Exception;

    /**
     * Returns the human-readable provider name for log/exception messages.
     *
     * @return the provider name
     */
    protected abstract String providerName();

    /**
     * Executes the LLM request. Orchestrates the full lifecycle: request invocation, audit logging,
     * and response parsing.
     *
     * @param request the resolved request containing the effective URL and API key
     * @return the normalized response from the LLM
     */
    @Override
    public final VetoResponse execute(ResolvedRequest request) {
        String requestId = UUID.randomUUID().toString();
        try {
            LlmClient.RawCompletion raw = invoke(request);
            auditLogger.logLLMExchange(
                    requestId, request.modelName(), raw.requestSummary(), raw.rawResponse());
            return parse(raw.rawResponse());
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw classify(e, request.modelName());
        }
    }

    private VetoResponse parse(String rawResponse) {
        try {
            return objectMapper.readValue(rawResponse, VetoResponse.class);
        } catch (Exception e) {
            throw new ModelCapabilityException(
                    providerName() + " response could not be parsed into VetoResponse", e);
        }
    }

    /**
     * Best-effort mapping of an SDK/transport exception to a typed, retryable-aware LlmException.
     */
    protected LlmException classify(Exception e, String model) {
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        if (e instanceof java.net.SocketTimeoutException
                || msg.contains("timeout")
                || msg.contains("timed out")) {
            return new LlmTimeoutException(providerName() + " timed out for model: " + model, e);
        }
        if (msg.contains("429")
                || msg.contains("rate limit")
                || msg.contains("too many requests")) {
            return new LlmRateLimitException(
                    providerName() + " rate limited for model: " + model, e);
        }
        if (msg.contains("401")
                || msg.contains("403")
                || msg.contains("unauthorized")
                || msg.contains("authentication")) {
            return new LlmAuthException(
                    providerName() + " authentication failed for model: " + model, e);
        }
        return new ModelCapabilityException(providerName() + " call failed for model: " + model, e);
    }
}
