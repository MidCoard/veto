package top.focess.veto.llm.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.translation.CapabilityTranslator;
import top.focess.veto.llm.core.LlmOptions;
import top.focess.veto.llm.core.ResolvedRequest;
import top.focess.veto.llm.core.VetoRequest;
import top.focess.veto.llm.exceptions.ModelCapabilityException;

/**
 * Adapter wrapping an {@link OpenAIClient} for OpenAI and OpenAI-compatible providers (DeepSeek,
 * OpenRouter, etc.).
 *
 * <p>All OpenAI SDK types are confined to this class. Providers never see them.
 */
final class OpenAiLlmClient extends LlmClient {

    private final @NonNull OpenAIClient sdkClient;
    private final boolean supportsJsonSchema;
    private final @NonNull String providerName;
    private final @NonNull ObjectMapper objectMapper;
    private final @NonNull CapabilityTranslator capabilityTranslator;

    OpenAiLlmClient(
            OpenAIClient sdkClient,
            boolean supportsJsonSchema,
            String providerName,
            ObjectMapper objectMapper,
            CapabilityTranslator capabilityTranslator) {
        this.sdkClient = sdkClient;
        this.supportsJsonSchema = supportsJsonSchema;
        this.providerName = providerName;
        this.objectMapper = objectMapper;
        this.capabilityTranslator = capabilityTranslator;
    }

    @Override
    public @NonNull RawCompletion complete(@NonNull ResolvedRequest resolved) {
        VetoRequest request = resolved.request();
        JsonNode rawSchema =
                request.responseSchema() != null
                        ? request.responseSchema()
                        : capabilityTranslator.vetoResponseSchema(false);
        Map<String, Object> responseSchema =
                objectMapper.convertValue(rawSchema, new TypeReference<Map<String, Object>>() {});
        String systemPrompt = request.systemPrompt();

        ChatCompletionCreateParams.Builder builder =
                ChatCompletionCreateParams.builder().model(ChatModel.of(request.modelName()));

        if (supportsJsonSchema) {
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
            systemPrompt = augmentPromptWithSchema(systemPrompt, responseSchema);
            builder.responseFormat(
                    ChatCompletionCreateParams.ResponseFormat.ofJsonObject(
                            ResponseFormatJsonObject.builder().build()));
        }

        builder.addMessage(
                        ChatCompletionMessageParam.ofSystem(
                                ChatCompletionSystemMessageParam.builder()
                                        .content(systemPrompt)
                                        .build()))
                .addMessage(
                        ChatCompletionMessageParam.ofUser(
                                ChatCompletionUserMessageParam.builder()
                                        .content(request.userPrompt())
                                        .build()));
        applyOptions(builder, request.options());

        ChatCompletion completion = sdkClient.chat().completions().create(builder.build());
        if (completion.usage().isPresent()) {
            var usage = completion.usage().get();
            top.focess.veto.llm.core.LlmSystemUsage.set(
                    usage.promptTokens(), usage.completionTokens());
        }
        String content =
                completion
                        .choices()
                        .get(0)
                        .message()
                        .content()
                        .orElseThrow(
                                () ->
                                        new ModelCapabilityException(
                                                providerName + " returned empty content"));

        String summary =
                "model="
                        + request.modelName()
                        + ", tools="
                        + request.tools().size()
                        + ", jsonSchema="
                        + supportsJsonSchema;
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

    private String augmentPromptWithSchema(
            String systemPrompt, Map<String, Object> responseSchema) {
        try {
            String schemaJson =
                    objectMapper
                            .writerWithDefaultPrettyPrinter()
                            .writeValueAsString(responseSchema);
            return systemPrompt
                    + "\n\nIMPORTANT: You must respond with a valid JSON object matching this schema:\n"
                    + schemaJson
                    + "\nEnsure the 'json' keyword is mentioned in your reasoning.";
        } catch (Exception e) {
            throw new ModelCapabilityException(
                    providerName + " failed to serialize response schema", e);
        }
    }
}
