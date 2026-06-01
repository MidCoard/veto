package top.focess.veto.llm.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import top.focess.veto.llm.client.LlmClientFactory;
import top.focess.veto.llm.config.LlmJacksonConfig;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.llm.core.ResolvedRequest;
import top.focess.veto.llm.core.VetoRequest;
import top.focess.veto.llm.schema.SchemaNormalizerService;
import top.focess.veto.observability.AuditLogger;

/**
 * Gemini provider: native JSON mode with a Gemini-dialect response schema.
 */
@Component
public class GeminiProvider extends AbstractLlmProvider {
    private final LlmClientFactory clientFactory;

    /**
     * Constructs a new GeminiProvider with the specified dependencies.
     *
     * @param objectMapper     the mapper for JSON serialization
     * @param schemaNormalizer the service for normalizing schemas
     * @param auditLogger      the logger for auditing requests
     * @param clientFactory    the factory for creating LLM clients
     */
    public GeminiProvider(
            @Qualifier(LlmJacksonConfig.LLM_OBJECT_MAPPER) ObjectMapper objectMapper,
            SchemaNormalizerService schemaNormalizer,
            AuditLogger auditLogger,
            LlmClientFactory clientFactory) {
        super(objectMapper, schemaNormalizer, auditLogger);
        this.clientFactory = clientFactory;
    }

    @Override
    public boolean supports(ProviderType providerType) {
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
    protected RawCompletion invoke(ResolvedRequest resolved) {
        VetoRequest request = resolved.request();
        Client client = clientFactory.gemini(resolved.baseUrl(), resolved.apiKey());
        Schema responseSchema =
                objectMapper.convertValue(
                        schemaNormalizer.buildGeminiResponseSchema(request.tools()), Schema.class);
        GenerateContentConfig.Builder configBuilder =
                GenerateContentConfig.builder()
                        .systemInstruction(Content.fromParts(Part.fromText(request.systemPrompt())))
                        .responseMimeType("application/json")
                        .responseSchema(responseSchema);
        if (request.options().temperature() != null) {
            configBuilder.temperature(request.options().temperature().floatValue());
        }
        if (request.options().maxTokens() != null) {
            configBuilder.maxOutputTokens(request.options().maxTokens());
        }
        GenerateContentResponse response =
                client.models.generateContent(
                        request.modelName(), request.userPrompt(), configBuilder.build());
        String summary = "model=" + request.modelName() + ", tools=" + request.tools().size();
        return new RawCompletion(summary, response.text());
    }
}
