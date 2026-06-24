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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import top.focess.veto.llm.core.ChatMessage;
import top.focess.veto.llm.core.ResolvedRequest;
import top.focess.veto.llm.core.VetoRequest;
import top.focess.veto.llm.exceptions.ModelCapabilityException;
import top.focess.veto.llm.schema.VetoPulseSchema;

/**
 * Adapter wrapping an {@link AnthropicClient}. Forces the single {@code veto_pulse} tool to emulate
 * structured output. All Anthropic SDK types are confined to this class.
 */
final class AnthropicLlmClient extends LlmClient {

    private static final String PULSE_TOOL = "veto_pulse";

    private final AnthropicClient sdkClient;
    private final ObjectMapper objectMapper;

    AnthropicLlmClient(AnthropicClient sdkClient, ObjectMapper objectMapper) {
        this.sdkClient = sdkClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public RawCompletion complete(ResolvedRequest resolved) {
        VetoRequest request = resolved.request();
        Map<String, Object> schema = responseSchemaMap(request);

        MessageCreateParams.Builder builder =
                MessageCreateParams.builder()
                        .model(Model.of(request.modelName()))
                        .maxTokens(request.options().maxTokensOrDefault())
                        .system(systemPrompt(request))
                        .addUserMessage(userContent(request))
                        .addTool(
                                Tool.builder()
                                        .name(PULSE_TOOL)
                                        .description(
                                                "Unified response format for Veto agent actions.")
                                        .inputSchema(
                                                Tool.InputSchema.builder()
                                                        .properties(JsonValue.from(schema))
                                                        .build())
                                        .build())
                        .toolChoice(
                                ToolChoice.ofTool(
                                        ToolChoiceTool.builder().name(PULSE_TOOL).build()));

        if (request.options().temperature() != null) {
            builder.temperature(request.options().temperature());
        }

        Message message = sdkClient.messages().create(builder.build());
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

    private String systemPrompt(VetoRequest request) {
        if (request.hasMessages()) {
            for (ChatMessage m : request.messages()) {
                if ("system".equals(m.role())) {
                    return m.content();
                }
            }
        }
        return request.systemPrompt();
    }

    private String userContent(VetoRequest request) {
        if (!request.hasMessages()) {
            return request.userPrompt();
        }
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : request.messages()) {
            if ("system".equals(m.role())) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(m.role()).append(": ").append(m.content());
        }
        return sb.length() == 0 ? request.userPrompt() : sb.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> responseSchemaMap(VetoRequest request) {
        JsonNode node = request.responseSchema();
        if (node != null && !node.isNull()) {
            return objectMapper.convertValue(node, Map.class);
        }
        return VetoPulseSchema.defaultAutonomousThoughtOn();
    }
}
