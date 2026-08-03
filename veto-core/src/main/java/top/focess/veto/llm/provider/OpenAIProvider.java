package top.focess.veto.llm.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import top.focess.veto.llm.client.LlmClientFactory;
import top.focess.veto.llm.config.LlmJacksonConfig;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.observability.AuditLogger;

/** OpenAI provider: stock base URL, strict Structured Outputs (json_schema). */
@Component
public class OpenAIProvider extends OpenAiCompatibleProvider {

    public OpenAIProvider(
            @Qualifier(LlmJacksonConfig.LLM_OBJECT_MAPPER) @NonNull ObjectMapper objectMapper,
            @NonNull AuditLogger auditLogger,
            @NonNull LlmClientFactory clientFactory) {
        super(objectMapper, auditLogger, clientFactory);
    }

    @Override
    public boolean supports(@NonNull ProviderType providerType) {
        return providerType == ProviderType.OPENAI;
    }

    @Override
    protected @NonNull String providerName() {
        return "OpenAI";
    }

    @Override
    public @Nullable String defaultBaseUrl() {
        return null;
    }

    @Override
    protected boolean supportsJsonSchema() {
        return true;
    }
}
