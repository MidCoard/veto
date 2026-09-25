package top.focess.veto.providers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.models.ChatModel;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.llm.ChatMessage;
import top.focess.veto.api.llm.LlmClient;
import top.focess.veto.api.llm.LlmOptions;
import top.focess.veto.api.llm.LlmSystemUsage;
import top.focess.veto.api.llm.PromptRenderer;
import top.focess.veto.api.llm.ResolvedRequest;
import top.focess.veto.api.llm.VetoRequest;
import top.focess.veto.api.llm.exceptions.ModelCapabilityException;
import top.focess.veto.api.llm.exceptions.ModelSchemaException;

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
    private final @NonNull PromptRenderer prompts;

    OpenAiLlmClient(
            @NonNull OpenAIClient sdkClient,
            boolean supportsJsonSchema,
            @NonNull String providerName,
            @NonNull ObjectMapper objectMapper,
            @NonNull PromptRenderer prompts) {
        this.sdkClient = sdkClient;
        this.supportsJsonSchema = supportsJsonSchema;
        this.providerName = providerName;
        this.objectMapper = objectMapper;
        this.prompts = prompts;
    }

    @Override
    public @NonNull RawCompletion complete(@NonNull ResolvedRequest resolved) {
        VetoRequest request = resolved.request();
        String systemPrompt = NativeToolResponses.prompt(prompts, request);

        ChatCompletionCreateParams.Builder builder =
                ChatCompletionCreateParams.builder().model(ChatModel.of(request.modelName()));

        if (NativeToolResponses.enabled(request)) {
            for (var tool : request.tools()) {
                var parameters = FunctionParameters.builder();
                tool.inputSchema()
                        .forEach(
                                (key, value) ->
                                        parameters.putAdditionalProperty(
                                                key, JsonValue.from(value)));
                builder.addTool(
                        ChatCompletionFunctionTool.builder()
                                .function(
                                        FunctionDefinition.builder()
                                                .name(tool.name())
                                                .description(tool.description())
                                                .parameters(parameters.build())
                                                .strict(false)
                                                .build())
                                .build());
            }
            builder.putAdditionalBodyProperty("tool_choice", JsonValue.from("auto"));
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
        if (request.messages().isEmpty())
            builder.addMessage(
                    ChatCompletionUserMessageParam.builder().content(request.userPrompt()).build());
        boolean reasoning = ModelReasoning.openAi(request.modelName());
        if (reasoning)
            builder.putAdditionalBodyProperty("reasoning_effort", JsonValue.from("medium"));
        applyOptions(builder, request.options(), reasoning);

        ChatCompletion completion = sdkClient.chat().completions().create(builder.build());
        if (completion.usage().isPresent()) {
            var usage = completion.usage().get();
            LlmSystemUsage.set(
                    usage.promptTokens(),
                    usage.completionTokens(),
                    usage.promptTokensDetails()
                            .flatMap(details -> details.cachedTokens())
                            .orElse(null),
                    null);
        }
        if (completion.choices().isEmpty())
            throw new ModelCapabilityException(providerName + " returned no choices");
        if (Set.of("length", "content_filter")
                .contains(completion.choices().getFirst().finishReason().toString()))
            throw new ModelSchemaException(
                    "OpenAI returned an incomplete response; no calls were executed");
        var message = completion.choices().getFirst().message();
        var calls = new ArrayList<NativeToolResponses.Call>();
        for (var toolCall : message.toolCalls().orElse(List.of())) {
            if (!toolCall.isFunction())
                throw new ModelSchemaException("Unsupported native tool call type");
            var function = toolCall.asFunction();
            calls.add(
                    new NativeToolResponses.Call(
                            function.function().name(),
                            NativeToolResponses.arguments(
                                    objectMapper, function.function().arguments()),
                            function.id()));
        }
        String content =
                NativeToolResponses.normalize(
                        objectMapper,
                        request,
                        message.content().orElse(message.refusal().orElse("")),
                        calls);
        if (content.isBlank() && calls.isEmpty())
            throw new ModelCapabilityException(providerName + " returned empty content");

        String summary =
                "model="
                        + request.modelName()
                        + ", tools="
                        + request.tools().size()
                        + ", jsonSchema="
                        + supportsJsonSchema;
        return NativeToolResponses.completion(
                objectMapper, summary, content, calls, java.util.List.of());
    }

    private void applyOptions(
            ChatCompletionCreateParams.@NonNull Builder builder,
            @NonNull LlmOptions options,
            boolean reasoning) {
        if (!reasoning && options.temperature() != null) {
            builder.temperature(options.temperature());
        }
        if (!reasoning && options.topP() != null) {
            builder.topP(options.topP());
        }
        Integer maxTokens = options.maxTokens();
        if (maxTokens != null) {
            builder.maxCompletionTokens(maxTokens.longValue());
        }
    }

    /**
     * Converts a {@link ChatMessage} to the OpenAI SDK's {@link ChatCompletionMessageParam}.
     * Tool-call assistant messages carry native {@code tool_calls}; tool-result messages carry
     * {@code tool_call_id}. Both are linked by the shared {@code callId}.
     */
    private static @NonNull ChatCompletionMessageParam toSdkMessage(@NonNull ChatMessage msg) {
        switch (msg.role()) {
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
                                                msg.toolResultContentWithStatus()))
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
