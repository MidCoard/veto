package top.focess.veto.llm.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import top.focess.veto.llm.client.LlmClient;
import top.focess.veto.llm.client.LlmClientFactory;
import top.focess.veto.llm.core.ResolvedRequest;
import top.focess.veto.observability.AuditLogger;

/**
 * Shared base for OpenAI-compatible Chat Completions APIs. OpenAI and DeepSeek differ only in base
 * URL and structured-output capability — those two flags control which {@link LlmClient} adapter
 * the factory returns.
 *
 * <p>All SDK-specific logic lives in {@code OpenAiLlmClient}. This class is just wiring.
 */
public abstract class OpenAiCompatibleProvider extends AbstractLlmProvider {
    protected final LlmClientFactory clientFactory;

    /**
     * Constructs a new OpenAiCompatibleProvider with the specified dependencies.
     *
     * @param objectMapper the mapper for JSON serialization
     * @param auditLogger the logger for auditing requests
     * @param clientFactory the factory for creating LLM clients
     */
    protected OpenAiCompatibleProvider(
            ObjectMapper objectMapper, AuditLogger auditLogger, LlmClientFactory clientFactory) {
        super(objectMapper, auditLogger);
        this.clientFactory = clientFactory;
    }

    /**
     * Whether the provider supports strict {@code json_schema} (OpenAI) vs only {@code
     * json_object}.
     *
     * @return true if json_schema is supported, false otherwise
     */
    protected abstract boolean supportsJsonSchema();

    @Override
    protected LlmClient.RawCompletion invoke(ResolvedRequest resolved) throws Exception {
        return clientFactory
                .openAi(resolved.baseUrl(), resolved.apiKey(), supportsJsonSchema(), providerName())
                .complete(resolved);
    }
}
