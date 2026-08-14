package top.focess.veto.llm.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import top.focess.veto.llm.client.LlmClient;
import top.focess.veto.llm.client.LlmClientFactory;
import top.focess.veto.llm.config.LlmJacksonConfig;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.llm.core.ResolvedRequest;
import top.focess.veto.observability.AuditLogger;

/**
 * Anthropic provider: delegates to {@code AnthropicLlmClient} which forces tool-use for structured
 * output.
 */
@Component
public class AnthropicProvider extends AbstractLlmProvider {
    private final @NonNull LlmClientFactory clientFactory;

    public AnthropicProvider(
            @Qualifier(LlmJacksonConfig.LLM_OBJECT_MAPPER) @NonNull ObjectMapper objectMapper,
            @NonNull AuditLogger auditLogger,
            @NonNull LlmClientFactory clientFactory) {
        super(objectMapper, auditLogger);
        this.clientFactory = clientFactory;
    }

    @Override
    public boolean supports(@NonNull ProviderType providerType) {
        return providerType == ProviderType.ANTHROPIC;
    }

    @Override
    protected @NonNull String providerName() {
        return "Anthropic";
    }

    @Override
    public String defaultBaseUrl() {
        return null;
    }

    @Override
    protected LlmClient.@NonNull RawCompletion invoke(@NonNull ResolvedRequest resolved)
            throws Exception {
        return clientFactory.anthropic(resolved.baseUrl(), resolved.apiKey()).complete(resolved);
    }
}
