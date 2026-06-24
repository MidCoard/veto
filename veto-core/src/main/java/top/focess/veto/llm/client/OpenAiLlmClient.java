package top.focess.veto.llm.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.models.ChatModel;
import com.openai.models.ResponseFormatJsonObject;
import com.openai.models.ResponseFormatJsonSchema;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import java.util.Map;
import top.focess.veto.llm.core.ChatMessage;
import top.focess.veto.llm.core.LlmOptions;
import top.focess.veto.llm.core.ResolvedRequest;
import top.focess.veto.llm.core.VetoRequest;
import top.focess.veto.llm.exceptions.ModelCapabilityException;
import top.focess.veto.llm.schema.VetoPulseSchema;

/**
 * Adapter wrapping an {@link OpenAIClient} for OpenAI and OpenAI-compatible providers (DeepSeek,
 * OpenRouter, etc.).
 *
 * <p>All OpenAI SDK types are confined to this class. Providers never see them.
 */
final class OpenAiLlmClient extends LlmClient {

    private final OpenAIClient sdkClient;
    private final boolean supportsJsonSchema;
    private final String providerName;
    private final ObjectMapper objectMapper;

    OpenAiLlmClient(
            OpenAIClient sdkClient,
            boolean supportsJsonSchema,
            String providerName,
            ObjectMapper objectMapper) {
        this.sdkClient = sdkClient;
        this.supportsJsonSchema = supportsJsonSchema;
        this.providerName = providerName;
        this.objectMapper = objectMapper;
    }

    @Override
    public RawCompletion complete(ResolvedRequest resolved) {
        VetoRequest request = resolved.request();
        Map<String, Object> responseSchema = responseSchemaMap(request);
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

        if (request.hasMessages()) {
            for (ChatMessage msg : request.messages()) {
                builder.addMessage(toMessageParam(msg));
            }
        } else {
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
        }
        applyOptions(builder, request.options());

        ChatCompletion completion = sdkClient.chat().completions().create(builder.build());
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

    private ChatCompletionMessageParam toMessageParam(ChatMessage msg) {
        return switch (msg.role()) {
            case "system" ->
                    ChatCompletionMessageParam.ofSystem(
                            ChatCompletionSystemMessageParam.builder()
                                    .content(msg.content())
                                    .build());
            case "assistant" ->
                    ChatCompletionMessageParam.ofAssistant(
                            ChatCompletionAssistantMessageParam.builder()
                                    .content(msg.content())
                                    .build());
                // Tool-role observations are rendered as user messages for MVP (provider-agnostic;
                // the content is self-describing "Observation (tool): ..."). The OpenAI tool role
                // requires a tool_call_id the compiler does not pair here.
            default ->
                    ChatCompletionMessageParam.ofUser(
                            ChatCompletionUserMessageParam.builder()
                                    .content(msg.content())
                                    .build());
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> responseSchemaMap(VetoRequest request) {
        JsonNode node = request.responseSchema();
        if (node != null && !node.isNull()) {
            return objectMapper.convertValue(node, Map.class);
        }
        return VetoPulseSchema.defaultAutonomousThoughtOn();
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
