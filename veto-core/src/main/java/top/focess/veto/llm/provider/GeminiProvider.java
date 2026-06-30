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

/** Gemini provider: delegates to {@code GeminiLlmClient} which uses native JSON mode. */
@Component
public class GeminiProvider extends AbstractLlmProvider {
    private final @NonNull LlmClientFactory clientFactory;

    public GeminiProvider(
            @Qualifier(LlmJacksonConfig.LLM_OBJECT_MAPPER) ObjectMapper objectMapper,
            AuditLogger auditLogger,
            LlmClientFactory clientFactory) {
        super(objectMapper, auditLogger);
        this.clientFactory = clientFactory;
    }

    @Override
    public boolean supports(@NonNull ProviderType providerType) {
        return providerType == ProviderType.GEMINI;
    }

    @Override
    protected String providerName() {
        return "Gemini";
    }

    @Override
    public String defaultBaseUrl() {
        return null;
    }

    @Override
    protected LlmClient.@NonNull RawCompletion invoke(@NonNull ResolvedRequest resolved)
            throws Exception {
        return clientFactory.gemini(resolved.baseUrl(), resolved.apiKey()).complete(resolved);
    }
}
