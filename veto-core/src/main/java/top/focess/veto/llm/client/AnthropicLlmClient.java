package top.focess.veto.llm.client;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.agent.loop.PromptLibrary;
import top.focess.veto.agent.translation.VetoCapabilityTranslator;
import top.focess.veto.llm.core.ChatMessage;
import top.focess.veto.llm.core.LlmSystemUsage;
import top.focess.veto.llm.core.ProviderMessages;
import top.focess.veto.llm.core.ResolvedRequest;
import top.focess.veto.llm.core.ToolDefinition;
import top.focess.veto.llm.core.VetoRequest;
import top.focess.veto.llm.exceptions.ModelCapabilityException;
import top.focess.veto.llm.exceptions.ModelSchemaException;

/**
 * Anthropic Messages adapter with strict native tools and structured JSON text output.
 * The configured endpoint must support both features and the supplied schemas. Requests do not
 * silently downgrade based on endpoint or model names; provider schema errors remain visible.
 * Native tool blocks are normalized into the runtime response envelope.
 *
 * <p>All Anthropic SDK types are confined to this class.
 */
final class AnthropicLlmClient extends LlmClient {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.llm.client.AnthropicLlmClient");

    private final @NonNull AnthropicClient sdkClient;
    private final @NonNull ObjectMapper objectMapper;

    AnthropicLlmClient(@NonNull AnthropicClient sdkClient, @NonNull ObjectMapper objectMapper) {
        this.sdkClient = sdkClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public @NonNull RawCompletion complete(@NonNull ResolvedRequest resolved) {
        VetoRequest request = resolved.request();

        MessageCreateParams.Builder builder =
                MessageCreateParams.builder()
                        .model(Model.of(request.modelName()))
                        .maxTokens(request.options().maxTokensOrDefault())
                        .system(responsePrompt(request));
        JsonNode outputSchema = request.responseSchema();
        if (outputSchema == null) {
            outputSchema =
                    new VetoCapabilityTranslator().vetoResponseSchema(false, request.tools());
        }
        builder.putAdditionalBodyProperty(
                "output_config",
                JsonValue.from(
                        Map.of(
                                "format",
                                Map.of(
                                        "type",
                                        "json_schema",
                                        "schema",
                                        objectMapper.convertValue(
                                                outputSchema,
                                                new TypeReference<Map<String, Object>>() {})))));
        Double temperature = request.options().temperature();
        if (temperature != null) {
            builder.putAdditionalBodyProperty("temperature", JsonValue.from(temperature));
        }
        if (!request.tools().isEmpty()) {
            builder.putAdditionalBodyProperty(
                    "tool_choice",
                    JsonValue.from(Map.of("type", permitsNativeCalls(request) ? "auto" : "none")));
        }
        // Retain tool contracts and native history while choosing the response channel.
        for (ToolDefinition t : request.tools()) {
            builder.addTool(
                    Tool.builder()
                            .name(t.name())
                            .description(t.description())
                            .putAdditionalProperty("strict", JsonValue.from(true))
                            .inputSchema(toolInputSchema(t.inputSchema()))
                            .build());
        }

        List<MessageParam> messageParams = toMessageParams(request);
        if (log.isDebugEnabled()) {
            log.debug(
                    "Anthropic outgoing history ({} compiled msgs):\n{}",
                    request.messages().size(),
                    describeHistory(request.messages()));
        }
        for (MessageParam messageParam : messageParams) {
            builder.addMessage(messageParam);
        }

        Message message = sdkClient.messages().create(builder.build());
        LlmSystemUsage.set(
                message.usage().inputTokens()
                        + message.usage().cacheReadInputTokens().orElse(0L)
                        + message.usage().cacheCreationInputTokens().orElse(0L),
                message.usage().outputTokens(),
                message.usage().cacheReadInputTokens().orElse(null),
                message.usage().cacheCreationInputTokens().orElse(null));
        if (log.isDebugEnabled()) {
            log.debug("Anthropic raw response blocks: {}", describeBlocks(message));
        }

        List<ToolUseBlock> toolUses =
                message.content().stream()
                        .filter(ContentBlock::isToolUse)
                        .map(ContentBlock::asToolUse)
                        .toList();
        String text =
                message.content().stream()
                        .filter(ContentBlock::isText)
                        .map(cb -> cb.asText().text())
                        .collect(Collectors.joining("\n"))
                        .strip();

        String rawInput;
        if (!toolUses.isEmpty()) {
            NativeToolResponses.validateNativeChannel(objectMapper, request, text);
            rawInput =
                    NativeToolResponses.normalize(
                            objectMapper,
                            request,
                            text,
                            toolUses.stream()
                                    .map(
                                            tu ->
                                                    new NativeToolResponses.Call(
                                                            tu.name(),
                                                            objectMapper.valueToTree(
                                                                    toolInputMap(tu)),
                                                            tu.id()))
                                    .toList());
        } else {
            if (text.isEmpty()) {
                throw new ModelCapabilityException(
                        "Anthropic response contained neither text nor tool calls");
            }
            // Text-only answer. A model following the system prompt's veto_pulse instructions may
            // emit the response JSON as text. Preserve JSON-shaped output even if malformed,
            // so central validation retries it instead of presenting it as a final answer.
            String candidate = NativeToolResponses.responseCandidate(text);
            if (candidate.stripLeading().startsWith("{")
                    || candidate.stripLeading().startsWith("[")) {
                rawInput = candidate;
            } else {
                if (text.contains("]<]minimax[>[")) {
                    throw new ModelSchemaException(
                            "Response contains internal tool markers instead of an executable response;"
                                    + " use native tool calls, JSON guide, or a final message");
                }
                var pulse = objectMapper.createObjectNode();
                pulse.put("message", text);
                rawInput = pulse.toString();
            }
        }

        String summary = "model=" + request.modelName() + ", tools=" + request.tools().size();
        return new RawCompletion(summary, rawInput);
    }

    private @NonNull String responsePrompt(@NonNull VetoRequest request) {
        JsonNode schema = request.responseSchema();
        if (schema == null) {
            return request.systemPrompt();
        }
        return PromptLibrary.compile(
                        "provider-anthropic",
                        Map.of(
                                "system",
                                request.systemPrompt(),
                                "schema",
                                schema,
                                "nativeCalls",
                                permitsNativeCalls(request),
                                "jsonCalls",
                                schema.path("properties").has("calls"),
                                "guide",
                                schema.path("properties").has("guide")))
                .text();
    }

    private boolean permitsNativeCalls(@NonNull VetoRequest request) {
        JsonNode schema = request.responseSchema();
        return !request.tools().isEmpty()
                && (schema == null || schema.path("properties").has("calls"));
    }

    private static Tool.InputSchema.@NonNull Properties toolProperties(
            @NonNull Map<String, Object> inputSchema) {
        Object value = inputSchema.get("properties");
        Tool.InputSchema.Properties.Builder builder = Tool.InputSchema.Properties.builder();
        if (value == null) {
            return builder.build();
        }
        if (!(value instanceof Map<?, ?> properties)) {
            throw new ModelCapabilityException("Anthropic tool properties must be a JSON object");
        }
        for (Map.Entry<?, ?> entry : properties.entrySet()) {
            if (!(entry.getKey() instanceof String name)) {
                throw new ModelCapabilityException("Anthropic tool property names must be strings");
            }
            builder.putAdditionalProperty(name, JsonValue.from(entry.getValue()));
        }
        return builder.build();
    }

    private static Tool.@NonNull InputSchema toolInputSchema(
            @NonNull Map<String, Object> inputSchema) {
        Tool.InputSchema.Builder builder =
                Tool.InputSchema.builder().properties(toolProperties(inputSchema));
        Object required = inputSchema.get("required");
        if (required != null) {
            builder.putAdditionalProperty("required", JsonValue.from(required));
        }
        Object additionalProperties = inputSchema.get("additionalProperties");
        if (additionalProperties != null) {
            builder.putAdditionalProperty(
                    "additionalProperties", JsonValue.from(additionalProperties));
        }
        return builder.build();
    }

    /**
     * Maps the compiled conversation to Anthropic message params, merging consecutive same-role
     * messages (the API requires strict role alternation). System messages are dropped - the
     * request's system prompt rides in {@code system(...)}. Tool results become user-role {@code
     * tool_result} blocks keyed by tool_use_id; synthetic observations (null callId) become plain
     * user text, matching the compiler's intent.
     */
    private @NonNull List<MessageParam> toMessageParams(@NonNull VetoRequest request) {
        // The PromptCompiler.wellFormed contract already guarantees a conversation every strict
        // provider accepts (opens on a user message; tool_use/tool_result pairs intact), so this
        // adapter maps messages directly and carries no provider-specific pairing guards.
        List<ChatMessage> history = request.messages();
        if (history.isEmpty()) {
            return List.of(
                    MessageParam.builder()
                            .role(MessageParam.Role.USER)
                            .content(request.userPrompt())
                            .build());
        }
        List<MessageParam> out = new ArrayList<>();
        for (var group : ProviderMessages.groups(request)) {
            MessageParam.Role groupRole =
                    group.getFirst().role().equals("assistant")
                            ? MessageParam.Role.ASSISTANT
                            : MessageParam.Role.USER;
            List<ContentBlockParam> groupBlocks = new ArrayList<>();
            for (ChatMessage m : group) {

                List<ContentBlockParam> blocks = new ArrayList<>();
                switch (m.role()) {
                    case "system" -> {
                        continue;
                    }
                    case "assistant" -> {
                        if (!m.content().isEmpty()) {
                            blocks.add(
                                    ContentBlockParam.ofText(
                                            TextBlockParam.builder().text(m.content()).build()));
                        }
                        String toolName = m.toolName();
                        String callId = m.callId();
                        if (toolName != null && callId != null) {
                            blocks.add(
                                    ContentBlockParam.ofToolUse(
                                            ToolUseBlockParam.builder()
                                                    .id(callId)
                                                    .name(toolName)
                                                    .input(toolInputParam(m.toolArgs()))
                                                    .build()));
                        }
                    }
                    case "tool" -> {
                        String callId = m.callId();
                        if (callId != null && !callId.isBlank()) {
                            blocks.add(
                                    ContentBlockParam.ofToolResult(
                                            ToolResultBlockParam.builder()
                                                    .toolUseId(callId)
                                                    .content(m.content())
                                                    .isError(Boolean.FALSE.equals(m.toolSuccess()))
                                                    .build()));
                        } else {
                            blocks.add(
                                    ContentBlockParam.ofText(
                                            TextBlockParam.builder().text(m.content()).build()));
                        }
                    }
                    default ->
                            blocks.add(
                                    ContentBlockParam.ofText(
                                            TextBlockParam.builder().text(m.content()).build()));
                }
                groupBlocks.addAll(blocks);
            }
            out.add(buildParam(groupRole, groupBlocks));
        }
        return out;
    }

    private static @NonNull MessageParam buildParam(
            MessageParam.@NonNull Role role, @NonNull List<ContentBlockParam> blocks) {
        return MessageParam.builder().role(role).contentOfBlockParams(blocks).build();
    }

    /**
     * Renders the compiled history (role + callId + toolName + content length) for the debug log.
     */
    private @NonNull String describeHistory(@NonNull List<ChatMessage> history) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : history) {
            sb.append('[').append(m.role()).append(']');
            if (m.callId() != null) {
                sb.append(" callId=").append(m.callId());
            }
            if (m.toolName() != null) {
                sb.append(" tool=").append(m.toolName());
            }
            sb.append(" contentLen=").append(m.content().length());
            sb.append('\n');
        }
        return sb.toString().strip();
    }

    /** Parses a tool-call args JSON string into the SDK's input param; empty input on bad JSON. */
    private ToolUseBlockParam.@NonNull Input toolInputParam(String toolArgs) {
        ToolUseBlockParam.Input.Builder input = ToolUseBlockParam.Input.builder();
        if (toolArgs != null && !toolArgs.isBlank()) {
            try {
                Map<String, Object> args =
                        objectMapper.readValue(toolArgs, new TypeReference<>() {});
                args.forEach((k, v) -> input.putAdditionalProperty(k, JsonValue.from(v)));
            } catch (Exception e) {
                log.debug(
                        "AnthropicLlmClient: unparseable tool args, sending empty input: {}",
                        toolArgs);
            }
        }
        return input.build();
    }

    /** A response tool_use block's input as a plain map (never the Java-Map {@code toString()}). */
    private @NonNull Map<String, Object> toolInputMap(@NonNull ToolUseBlock tu) {
        try {
            Map<String, Object> converted = tu._input().convert(new TypeReference<>() {});
            if (converted == null) {
                throw new ModelSchemaException("Anthropic tool input decoded to null");
            }
            return converted;
        } catch (Exception e) {
            throw new ModelSchemaException(
                    "Anthropic tool input was not a JSON object: " + e.getMessage());
        }
    }

    /**
     * Renders the response's content blocks for the debug log: text blocks verbatim (truncated),
     * tool_use blocks as name + Jackson-serialized input (never the Java-Map {@code toString()}).
     */
    private @NonNull String describeBlocks(@NonNull Message message) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : message.content()) {
            if (block.isText()) {
                String t = block.asText().text();
                sb.append("[text] ")
                        .append(t.length() > 2000 ? t.substring(0, 2000) + "..." : t)
                        .append('\n');
            } else if (block.isToolUse()) {
                var tu = block.asToolUse();
                sb.append("[tool_use] ").append(tu.name()).append(' ');
                try {
                    sb.append(objectMapper.writeValueAsString(toolInputMap(tu)));
                } catch (Exception e) {
                    sb.append("<unserializable input: ").append(e.getMessage()).append('>');
                }
                sb.append('\n');
            } else {
                sb.append("[other block]\n");
            }
        }
        return sb.toString().strip();
    }
}
