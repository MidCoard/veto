package top.focess.veto.llm.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import top.focess.veto.llm.client.LlmClientFactory;
import top.focess.veto.llm.config.LlmJacksonConfig;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.observability.AuditLogger;

/**
 * OpenAI provider: stock base URL, strict Structured Outputs (json_schema).
 */
@Component
public class OpenAIProvider extends OpenAiCompatibleProvider {

    public OpenAIProvider(
            @Qualifier(LlmJacksonConfig.LLM_OBJECT_MAPPER) ObjectMapper objectMapper,
            AuditLogger auditLogger,
            LlmClientFactory clientFactory) {
        super(objectMapper, auditLogger, clientFactory);
    }

    @Override
    public boolean supports(ProviderType providerType) {
        return providerType == ProviderType.OPENAI;
    }

    @Override
    protected String providerName() {
        return "OpenAI";
    }

    @Override
    public String defaultBaseUrl() {
        return null;
    }

    @Override
    protected boolean supportsJsonSchema() {
        return true;
    }
}
