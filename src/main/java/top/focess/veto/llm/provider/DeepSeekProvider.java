package top.focess.veto.llm.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import top.focess.veto.llm.client.LlmClientFactory;
import top.focess.veto.llm.config.LlmJacksonConfig;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.observability.AuditLogger;

/**
 * DeepSeek provider: OpenAI-compatible API at a different base URL. DeepSeek only supports {@code
 * json_object}, so the adapter injects the response schema into the system prompt.
 */
@Component
public class DeepSeekProvider extends OpenAiCompatibleProvider {
    private static final String DEEPSEEK_BASE_URL = "https://api.deepseek.com";

    public DeepSeekProvider(
            @Qualifier(LlmJacksonConfig.LLM_OBJECT_MAPPER) ObjectMapper objectMapper,
            AuditLogger auditLogger,
            LlmClientFactory clientFactory) {
        super(objectMapper, auditLogger, clientFactory);
    }

    @Override
    public boolean supports(ProviderType providerType) {
        return providerType == ProviderType.DEEPSEEK;
    }

    @Override
    protected String providerName() {
        return "DeepSeek";
    }

    @Override
    public String defaultBaseUrl() {
        return DEEPSEEK_BASE_URL;
    }

    @Override
    protected boolean supportsJsonSchema() {
        return false;
    }
}
