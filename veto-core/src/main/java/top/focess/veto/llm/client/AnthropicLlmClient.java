package top.focess.veto.llm.client;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolChoice;
import com.anthropic.models.messages.ToolChoiceTool;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.translation.CapabilityTranslator;
import top.focess.veto.llm.core.ResolvedRequest;
import top.focess.veto.llm.core.VetoRequest;
import top.focess.veto.llm.exceptions.ModelCapabilityException;

/**
 * Adapter wrapping an {@link AnthropicClient}. Forces the single {@code veto_pulse} tool to emulate
 * structured output. All Anthropic SDK types are confined to this class.
 */
final class AnthropicLlmClient extends LlmClient {

    private static final String PULSE_TOOL = "veto_pulse";

    private final @NonNull AnthropicClient sdkClient;
    private final @NonNull ObjectMapper objectMapper;
    private final @NonNull CapabilityTranslator capabilityTranslator;

    AnthropicLlmClient(
            AnthropicClient sdkClient,
            ObjectMapper objectMapper,
            CapabilityTranslator capabilityTranslator) {
        this.sdkClient = sdkClient;
        this.objectMapper = objectMapper;
        this.capabilityTranslator = capabilityTranslator;
    }

    @Override
    public @NonNull RawCompletion complete(@NonNull ResolvedRequest resolved) {
        VetoRequest request = resolved.request();
        JsonNode responseSchema =
                request.responseSchema() != null
                        ? request.responseSchema()
                        : capabilityTranslator.vetoResponseSchema(false);
        Map<String, Object> inputSchema =
                objectMapper.convertValue(
                        responseSchema, new TypeReference<Map<String, Object>>() {});

        MessageCreateParams.Builder builder =
                MessageCreateParams.builder()
                        .model(Model.of(request.modelName()))
                        .maxTokens(request.options().maxTokensOrDefault())
                        .system(request.systemPrompt())
                        .addUserMessage(request.userPrompt())
                        .addTool(
                                Tool.builder()
                                        .name(PULSE_TOOL)
                                        .description(
                                                "Unified response format for Veto agent actions.")
                                        .inputSchema(
                                                Tool.InputSchema.builder()
                                                        .properties(JsonValue.from(inputSchema))
                                                        .build())
                                        .build())
                        .toolChoice(
                                ToolChoice.ofTool(
                                        ToolChoiceTool.builder().name(PULSE_TOOL).build()));

        if (request.options().temperature() != null) {
            builder.temperature(request.options().temperature());
        }

        Message message = sdkClient.messages().create(builder.build());
        if (message.usage() != null) {
            top.focess.veto.llm.core.LlmSystemUsage.set(
                    message.usage().inputTokens(), message.usage().outputTokens());
        }
        String rawInput =
                message.content().stream()
                        .filter(ContentBlock::isToolUse)
                        .map(ContentBlock::asToolUse)
                        .filter(tu -> PULSE_TOOL.equals(tu.name()))
                        .map(tu -> tu._input().toString())
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new ModelCapabilityException(
                                                "Anthropic response did not contain the veto_pulse tool call"));

        String summary = "model=" + request.modelName() + ", tools=" + request.tools().size();
        return new RawCompletion(summary, rawInput);
    }
}
