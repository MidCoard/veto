package top.focess.veto.llm.provider;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolChoice;
import com.anthropic.models.messages.ToolChoiceTool;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import top.focess.veto.llm.client.LlmClientFactory;
import top.focess.veto.llm.config.LlmJacksonConfig;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.llm.core.ResolvedRequest;
import top.focess.veto.llm.core.VetoRequest;
import top.focess.veto.llm.exceptions.ModelCapabilityException;
import top.focess.veto.llm.schema.SchemaNormalizerService;
import top.focess.veto.observability.AuditLogger;

/**
 * Anthropic provider: forces the single {@code veto_pulse} tool to emulate structured output.
 */
@Component
public class AnthropicProvider extends AbstractLlmProvider {
    private static final String PULSE_TOOL = "veto_pulse";
    private final LlmClientFactory clientFactory;

    /**
     * Constructs a new AnthropicProvider with the specified dependencies.
     *
     * @param objectMapper     the mapper for JSON serialization
     * @param schemaNormalizer the service for normalizing schemas
     * @param auditLogger      the logger for auditing requests
     * @param clientFactory    the factory for creating LLM clients
     */
    public AnthropicProvider(
            @Qualifier(LlmJacksonConfig.LLM_OBJECT_MAPPER) ObjectMapper objectMapper,
            SchemaNormalizerService schemaNormalizer,
            AuditLogger auditLogger,
            LlmClientFactory clientFactory) {
        super(objectMapper, schemaNormalizer, auditLogger);
        this.clientFactory = clientFactory;
    }

    @Override
    public boolean supports(ProviderType providerType) {
        return providerType == ProviderType.ANTHROPIC;
    }

    @Override
    protected String providerName() {
        return "Anthropic";
    }

    @Override
    public String defaultBaseUrl() {
        return null;
    }

    @Override
    protected RawCompletion invoke(ResolvedRequest resolved) {
        VetoRequest request = resolved.request();
        AnthropicClient client = clientFactory.anthropic(resolved.baseUrl(), resolved.apiKey());
        Map<String, Object> toolSchema = schemaNormalizer.mapToAnthropicTools(request.tools()).get(0);
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
                                                        .properties(JsonValue.from(toolSchema.get("input_schema")))
                                                        .build())
                                        .build())
                        .toolChoice(ToolChoice.ofTool(ToolChoiceTool.builder().name(PULSE_TOOL).build()));
        if (request.options().temperature() != null) {
            builder.temperature(request.options().temperature());
        }
        Message message = client.messages().create(builder.build());
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
