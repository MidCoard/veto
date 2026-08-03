package top.focess.veto.llm.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.llm.client.LlmClient;
import top.focess.veto.llm.core.ResolvedRequest;
import top.focess.veto.llm.core.ToolDefinition;
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

    private static final Logger log = LoggerFactory.getLogger(AbstractLlmProvider.class);

    /**
     * Constructs a new AbstractLlmProvider with the specified dependencies.
     *
     * @param objectMapper the mapper for JSON serialization
     * @param auditLogger the logger for auditing requests
     */
    protected
    @NonNull
    AbstractLlmProvider(@NonNull ObjectMapper objectMapper, @NonNull AuditLogger auditLogger) {
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
    protected abstract LlmClient.@NonNull RawCompletion invoke(@NonNull ResolvedRequest request)
            throws Exception;

    /**
     * Returns the human-readable provider name for log/exception messages.
     *
     * @return the provider name
     */
    protected abstract @NonNull String providerName();

    /**
     * Executes the LLM request. Orchestrates the full lifecycle: request invocation, audit logging,
     * and response parsing.
     *
     * @param request the resolved request containing the effective URL and API key
     * @return the normalized response from the LLM
     */
    @Override
    public final @NonNull VetoResponse execute(@NonNull ResolvedRequest request) {
        String requestId = UUID.randomUUID().toString();
        log.debug(
                "LLM call start requestId={} provider={} model={}",
                requestId,
                providerName(),
                request.modelName());
        // This is the universal chokepoint - every provider (OpenAI, DeepSeek, Anthropic, Gemini)
        // flows through here, so logging here fires regardless of which LlmClient adapter the
        // provider picks. Per turn only the tool list is surfaced at this layer (each tool's name +
        // input schema) - that is the actionable surface for debugging tool/path resolution. The
        // system prompt, role-mapped message flow, userPrompt and responseSchema are NOT logged
        // here; the full exchange remains in the audit trail via auditLogger.logLLMExchange below.
        // No secrets live on VetoRequest - apiKey is on ResolvedRequest and is never logged.
        var vetoRequest = request.request();
        List<ToolDefinition> tools = vetoRequest.tools();
        log.debug("LLM raw request requestId={} tools ({}):", requestId, tools.size());
        for (ToolDefinition tool : tools) {
            log.debug(
                    "  [tool] {} params={}", tool.name(), describeInputSchema(tool.inputSchema()));
        }
        try {
            LlmClient.RawCompletion raw = invoke(request);
            log.debug(
                    "LLM raw response requestId={} ({} chars):\n{}",
                    requestId,
                    raw.rawResponse().length(),
                    raw.rawResponse());
            auditLogger.logLLMExchange(
                    requestId, request.modelName(), raw.requestSummary(), raw.rawResponse());
            VetoResponse response = parse(raw.rawResponse());
            // Parsed-field summary only (no raw payloads / no secrets). The calls count is the
            // loop signature: calls=0 means the turn stops (the runAutonomous no-calls
            // termination). is_finished was removed - termination routes on call presence.
            log.debug(
                    "LLM call done requestId={} model={} calls={} msgLen={} thoughtLen={}"
                            + " guided={}",
                    requestId,
                    request.modelName(),
                    response.hasCalls() ? response.calls().size() : 0,
                    response.message() != null ? response.message().length() : 0,
                    response.thought() != null ? response.thought().length() : 0,
                    response.features() != null ? response.features().guided() : null);
            return response;
        } catch (LlmException e) {
            log.debug(
                    "LLM call failed requestId={} model={} {}: {}",
                    requestId,
                    request.modelName(),
                    e.getClass().getSimpleName(),
                    e.getMessage());
            throw e;
        } catch (Exception e) {
            log.debug(
                    "LLM call failed requestId={} model={} {}: {}",
                    requestId,
                    request.modelName(),
                    e.getClass().getSimpleName(),
                    e.getMessage());
            throw classify(e, request.modelName());
        }
    }

    /**
     * Compact JSON rendering of a tool's input schema for debug logging, so the per-tool params
     * passed to the model are visible without dumping the full system prompt. Falls back to the
     * map's {@code toString} if serialization fails.
     */
    private @NonNull String describeInputSchema(@NonNull Map<String, Object> inputSchema) {
        try {
            return objectMapper.writeValueAsString(inputSchema);
        } catch (Exception e) {
            return String.valueOf(inputSchema);
        }
    }

    private @NonNull VetoResponse parse(@NonNull String rawResponse) {
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
    protected @NonNull LlmException classify(@NonNull Exception e, @NonNull String model) {
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
