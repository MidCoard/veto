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
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.translation.CapabilityTranslator;
import top.focess.veto.llm.core.ChatMessage;
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
            @NonNull OpenAIClient sdkClient,
            boolean supportsJsonSchema,
            @NonNull String providerName,
            @NonNull ObjectMapper objectMapper,
            @NonNull CapabilityTranslator capabilityTranslator) {
        this.sdkClient = sdkClient;
        this.supportsJsonSchema = supportsJsonSchema;
        this.providerName = providerName;
        this.objectMapper = objectMapper;
        this.capabilityTranslator = capabilityTranslator;
    }

    @Override
    public @NonNull RawCompletion complete(@NonNull ResolvedRequest resolved) {
        VetoRequest request = resolved.request();
        JsonNode configuredSchema = request.responseSchema();
        JsonNode rawSchema =
                configuredSchema != null
                        ? configuredSchema
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
                                                    .schema(responseSchemaOf(responseSchema))
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
                        ChatCompletionSystemMessageParam.builder().content(systemPrompt).build()));
        // Send the FULL conversation history (not just the last message). Each message is mapped
        // to its native SDK type: user -> user, assistant+callId -> assistant with tool_calls,
        // tool -> tool with tool_call_id. The call_id structurally links tool calls to results.
        for (ChatMessage msg : request.messages()) {
            if ("system".equals(msg.role())) {
                continue; // already added above
            }
            builder.addMessage(toSdkMessage(msg));
        }
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

    private static ResponseFormatJsonSchema.JsonSchema.@NonNull Schema responseSchemaOf(
            @NonNull Map<String, Object> responseSchema) {
        ResponseFormatJsonSchema.JsonSchema.Schema.Builder builder =
                ResponseFormatJsonSchema.JsonSchema.Schema.builder();
        responseSchema.forEach(
                (name, value) -> builder.putAdditionalProperty(name, JsonValue.from(value)));
        return builder.build();
    }

    private void applyOptions(
            ChatCompletionCreateParams.@NonNull Builder builder, @NonNull LlmOptions options) {
        if (options.temperature() != null) {
            builder.temperature(options.temperature());
        }
        if (options.topP() != null) {
            builder.topP(options.topP());
        }
        Integer maxTokens = options.maxTokens();
        if (maxTokens != null) {
            builder.maxCompletionTokens(maxTokens.longValue());
        }
    }

    private @NonNull String augmentPromptWithSchema(
            @NonNull String systemPrompt, @NonNull Map<String, Object> responseSchema) {
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

    /**
     * Converts a {@link ChatMessage} to the OpenAI SDK's {@link ChatCompletionMessageParam}.
     * Tool-call assistant messages carry native {@code tool_calls}; tool-result messages carry
     * {@code tool_call_id}. Both are linked by the shared {@code callId}.
     */
    private static @NonNull ChatCompletionMessageParam toSdkMessage(@NonNull ChatMessage msg) {
        switch (msg.role()) {
            case "user" -> {
                return ChatCompletionMessageParam.ofUser(
                        ChatCompletionUserMessageParam.builder().content(msg.content()).build());
            }
            case "assistant" -> {
                String callId = msg.callId();
                if (callId != null) {
                    String toolName = msg.toolName();
                    String toolArgs = msg.toolArgs();
                    // Native tool_call on the assistant message
                    ChatCompletionMessageFunctionToolCall.Function function =
                            ChatCompletionMessageFunctionToolCall.Function.builder()
                                    .name(toolName != null ? toolName : "")
                                    .arguments(toolArgs != null ? toolArgs : "{}")
                                    .build();
                    ChatCompletionMessageFunctionToolCall toolCall =
                            ChatCompletionMessageFunctionToolCall.builder()
                                    .id(callId)
                                    .function(function)
                                    .build();
                    ChatCompletionAssistantMessageParam.Builder ab =
                            ChatCompletionAssistantMessageParam.builder().addToolCall(toolCall);
                    if (!msg.content().isEmpty()) {
                        ab.content(msg.content());
                    }
                    return ChatCompletionMessageParam.ofAssistant(ab.build());
                }
                return ChatCompletionMessageParam.ofAssistant(
                        ChatCompletionAssistantMessageParam.builder()
                                .content(msg.content())
                                .build());
            }
            case "tool" -> {
                String callId = msg.callId();
                return ChatCompletionMessageParam.ofTool(
                        ChatCompletionToolMessageParam.builder()
                                .content(
                                        ChatCompletionToolMessageParam.Content.ofText(
                                                msg.content()))
                                .toolCallId(callId != null ? callId : "")
                                .build());
            }
            default -> {
                return ChatCompletionMessageParam.ofUser(
                        ChatCompletionUserMessageParam.builder().content(msg.content()).build());
            }
        }
    }
}
