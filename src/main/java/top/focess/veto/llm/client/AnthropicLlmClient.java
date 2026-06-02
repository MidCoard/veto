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
import java.util.Map;
import top.focess.veto.llm.core.ResolvedRequest;
import top.focess.veto.llm.core.VetoRequest;
import top.focess.veto.llm.exceptions.ModelCapabilityException;
import top.focess.veto.llm.schema.SchemaNormalizerService;

/**
 * Adapter wrapping an {@link AnthropicClient}. Forces the single {@code veto_pulse} tool to emulate
 * structured output. All Anthropic SDK types are confined to this class.
 */
final class AnthropicLlmClient extends LlmClient {

    private static final String PULSE_TOOL = "veto_pulse";

    private final AnthropicClient sdkClient;
    private final SchemaNormalizerService schemaNormalizer;

    AnthropicLlmClient(AnthropicClient sdkClient, SchemaNormalizerService schemaNormalizer) {
        this.sdkClient = sdkClient;
        this.schemaNormalizer = schemaNormalizer;
    }

    @Override
    public RawCompletion complete(ResolvedRequest resolved) {
        VetoRequest request = resolved.request();
        Map<String, Object> toolSchema =
                schemaNormalizer.mapToAnthropicTools(request.tools()).get(0);

        MessageCreateParams.Builder builder =
                MessageCreateParams.builder()
                        .model(Model.of(request.modelName()))
                        .maxTokens(request.options().maxTokensOrDefault())
                        .system(request.systemPrompt())
                        .addUserMessage(request.userPrompt())
                        .addTool(
                                Tool.builder()
                                        .name(toolSchema.get("name").toString())
                                        .description(toolSchema.get("description").toString())
                                        .inputSchema(
                                                Tool.InputSchema.builder()
                                                        .properties(
                                                                JsonValue.from(
                                                                        toolSchema.get(
                                                                                "input_schema")))
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
}
