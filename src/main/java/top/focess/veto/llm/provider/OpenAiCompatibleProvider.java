package top.focess.veto.llm.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.models.ChatModel;
import com.openai.models.ResponseFormatJsonObject;
import com.openai.models.ResponseFormatJsonSchema;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;

import java.util.Map;

import top.focess.veto.llm.client.LlmClientFactory;
import top.focess.veto.llm.core.LlmOptions;
import top.focess.veto.llm.core.ResolvedRequest;
import top.focess.veto.llm.core.VetoRequest;
import top.focess.veto.llm.exceptions.ModelCapabilityException;
import top.focess.veto.llm.schema.SchemaNormalizerService;
import top.focess.veto.observability.AuditLogger;

/**
 * Shared implementation for OpenAI-compatible Chat Completions APIs. OpenAI and DeepSeek differ
 * only in base URL and structured-output capability, so both reduce to thin subclasses that set
 * those two flags via {@link #defaultBaseUrl()} and {@link #supportsJsonSchema()}.
 */
public abstract class OpenAiCompatibleProvider extends AbstractLlmProvider {
    protected final LlmClientFactory clientFactory;

    /**
     * Constructs a new OpenAiCompatibleProvider with the specified dependencies.
     *
     * @param objectMapper     the mapper for JSON serialization
     * @param schemaNormalizer the service for normalizing schemas
     * @param auditLogger      the logger for auditing requests
     * @param clientFactory    the factory for creating LLM clients
     */
    protected OpenAiCompatibleProvider(
            ObjectMapper objectMapper,
            SchemaNormalizerService schemaNormalizer,
            AuditLogger auditLogger,
            LlmClientFactory clientFactory) {
        super(objectMapper, schemaNormalizer, auditLogger);
        this.clientFactory = clientFactory;
    }

    /**
     * Whether the provider supports strict {@code json_schema} (OpenAI) vs only {@code json_object}.
     *
     * @return true if json_schema is supported, false otherwise
     */
    protected abstract boolean supportsJsonSchema();

    @Override
    protected RawCompletion invoke(ResolvedRequest resolved) {
        VetoRequest request = resolved.request();
        OpenAIClient client = clientFactory.openAi(resolved.baseUrl(), resolved.apiKey());
        Map<String, Object> responseSchema =
                schemaNormalizer.buildOpenAIResponseSchema(request.tools());
        String systemPrompt = request.systemPrompt();
        ChatCompletionCreateParams.Builder builder =
                ChatCompletionCreateParams.builder().model(ChatModel.of(request.modelName()));
        if (supportsJsonSchema()) {
            builder.responseFormat(
                    ChatCompletionCreateParams.ResponseFormat.ofJsonSchema(
                            ResponseFormatJsonSchema.builder()
                                    .jsonSchema(
                                            ResponseFormatJsonSchema.JsonSchema.builder()
                                                    .name("veto_pulse")
                                                    .strict(true)
                                                    .schema(JsonValue.from(responseSchema))
                                                    .build())
                                    .build()));
        } else {
            // DeepSeek only supports json_object; inject the schema into the system prompt instead.
            systemPrompt = augmentPromptWithSchema(systemPrompt, responseSchema);
            builder.responseFormat(
                    ChatCompletionCreateParams.ResponseFormat.ofJsonObject(
                            ResponseFormatJsonObject.builder().build()));
        }
        builder
                .addMessage(
                        ChatCompletionMessageParam.ofSystem(
                                ChatCompletionSystemMessageParam.builder().content(systemPrompt).build()))
                .addMessage(
                        ChatCompletionMessageParam.ofUser(
                                ChatCompletionUserMessageParam.builder().content(request.userPrompt()).build()));
        applyOptions(builder, request.options());
        ChatCompletionCreateParams params = builder.build();
        ChatCompletion completion = client.chat().completions().create(params);
        String content =
                completion
                        .choices()
                        .get(0)
                        .message()
                        .content()
                        .orElseThrow(
                                () -> new ModelCapabilityException(providerName() + " returned empty content"));
        String summary =
                "model="
                        + request.modelName()
                        + ", tools="
                        + request.tools().size()
                        + ", jsonSchema="
                        + supportsJsonSchema();
        return new RawCompletion(summary, content);
    }

    private void applyOptions(ChatCompletionCreateParams.Builder builder, LlmOptions options) {
        if (options.temperature() != null) {
            builder.temperature(options.temperature());
        }
        if (options.topP() != null) {
            builder.topP(options.topP());
        }
        if (options.maxTokens() != null) {
            builder.maxCompletionTokens(options.maxTokens().longValue());
        }
    }

    private String augmentPromptWithSchema(String systemPrompt, Map<String, Object> responseSchema) {
        try {
            String schemaJson =
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(responseSchema);
            return systemPrompt
                    + "\n\nIMPORTANT: You must respond with a valid JSON object matching this schema:\n"
                    + schemaJson
                    + "\nEnsure the 'json' keyword is mentioned in your reasoning.";
        } catch (Exception e) {
            throw new ModelCapabilityException(
                    providerName() + " failed to serialize response schema", e);
        }
    }
}
