package top.focess.veto.providers;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.api.llm.ChatMessage;
import top.focess.veto.api.llm.LlmClient;
import top.focess.veto.api.llm.LlmSystemUsage;
import top.focess.veto.api.llm.NativeToolState;
import top.focess.veto.api.llm.PromptRenderer;
import top.focess.veto.api.llm.ProviderMessages;
import top.focess.veto.api.llm.ResolvedRequest;
import top.focess.veto.api.llm.ToolDefinition;
import top.focess.veto.api.llm.VetoRequest;
import top.focess.veto.api.llm.exceptions.ModelCapabilityException;
import top.focess.veto.api.llm.exceptions.ModelSchemaException;

/**
 * Anthropic Messages adapter with native tools and ordinary text output. Strict decoding is used
 * only when the unchanged tool schema fits Anthropic's supported subset and request limits. The
 * complete argument schemas are retained for every tool; provider schema errors remain visible.
 * Native tool blocks are decoded separately from ordinary text.
 *
 * <p>All Anthropic SDK types are confined to this class.
 */
final class AnthropicLlmClient extends LlmClient {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.providers.AnthropicLlmClient");

    private final @NonNull AnthropicClient sdkClient;
    private final @NonNull ObjectMapper objectMapper;
    private final @NonNull PromptRenderer prompts;

    AnthropicLlmClient(
            @NonNull AnthropicClient sdkClient,
            @NonNull ObjectMapper objectMapper,
            @NonNull PromptRenderer prompts) {
        this.sdkClient = sdkClient;
        this.objectMapper = objectMapper;
        this.prompts = prompts;
    }

    @Override
    public @NonNull RawCompletion complete(@NonNull ResolvedRequest resolved) throws Exception {
        VetoRequest request = resolved.request();

        MessageCreateParams.Builder builder =
                MessageCreateParams.builder()
                        .model(Model.of(request.modelName()))
                        .maxTokens(request.options().maxTokensOrDefault())
                        .system(responsePrompt(request));
        Double temperature = request.options().temperature();
        var thinking = ModelReasoning.anthropic(request);
        if (!thinking.isEmpty())
            builder.putAdditionalBodyProperty("thinking", JsonValue.from(thinking));
        if (temperature != null
                && (!request.modelName().startsWith("claude-") || thinking.isEmpty())) {
            builder.putAdditionalBodyProperty("temperature", JsonValue.from(temperature));
        }
        if (!request.tools().isEmpty()) {
            builder.putAdditionalBodyProperty(
                    "tool_choice",
                    JsonValue.from(Map.of("type", permitsNativeCalls(request) ? "auto" : "none")));
        }
        // Retain tool contracts and native history while choosing the response channel.
        var strictPolicy = new AnthropicStrictToolPolicy();
        for (ToolDefinition t : request.tools()) {
            var selection = strictPolicy.select(objectMapper.valueToTree(t.inputSchema()));
            if (!selection.strict())
                log.debug(
                        "Anthropic native tool {} uses local schema validation: {}",
                        t.name(),
                        selection.reason());
            builder.addTool(
                    Tool.builder()
                            .name(t.name())
                            .description(t.description())
                            .putAdditionalProperty("strict", JsonValue.from(selection.strict()))
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

        String stop = message.stopReason().map(Object::toString).orElse("");
        if (Set.of("max_tokens", "model_context_window_exceeded", "pause_turn").contains(stop))
            throw new ModelSchemaException(
                    "Anthropic response was truncated (stop_reason="
                            + stop
                            + ", tool_use_blocks="
                            + toolUses.size()
                            + "); no calls were executed");
        if ((!toolUses.isEmpty() && !stop.isEmpty() && !stop.equals("tool_use"))
                || (toolUses.isEmpty() && stop.equals("tool_use")))
            throw new ModelSchemaException(
                    "Anthropic stop_reason does not match its tool_use blocks (stop_reason="
                            + stop
                            + ", tool_use_blocks="
                            + toolUses.size()
                            + "); no calls were executed");
        String rawInput =
                NativeToolResponses.normalize(
                        objectMapper,
                        request,
                        text,
                        toolUses.stream()
                                .map(
                                        tu ->
                                                new NativeToolResponses.Call(
                                                        tu.name(),
                                                        objectMapper.valueToTree(toolInputMap(tu)),
                                                        tu.id()))
                                .toList());
        if (text.isBlank() && toolUses.isEmpty())
            throw new ModelSchemaException("Anthropic returned neither text nor tool calls");

        String summary = "model=" + request.modelName() + ", tools=" + request.tools().size();
        return NativeToolResponses.completion(
                        objectMapper,
                        summary,
                        rawInput,
                        toolUses.stream()
                                .map(
                                        tu ->
                                                new NativeToolResponses.Call(
                                                        tu.name(),
                                                        objectMapper.valueToTree(toolInputMap(tu)),
                                                        tu.id()))
                                .toList(),
                        nativeStates(message, request.modelName()))
                .withReasoning(
                        message.content().stream()
                                .filter(ContentBlock::isThinking)
                                .map(block -> block.asThinking().thinking())
                                .collect(Collectors.joining("\n")));
    }

    private static @NonNull List<NativeToolState> nativeStates(
            @NonNull Message message, @NonNull String model) throws Exception {
        var segments = new ArrayList<List<ContentBlockParam>>();
        var pending = new ArrayList<ContentBlockParam>();
        for (var block : message.content()) {
            pending.add(block.toParam());
            if (block.isToolUse()) {
                segments.add(new ArrayList<>(pending));
                pending.clear();
            }
        }
        if (!segments.isEmpty()) segments.getLast().addAll(pending);
        var result = new ArrayList<NativeToolState>();
        String batch = UUID.randomUUID().toString();
        for (var segment : segments)
            result.add(
                    new NativeToolState(
                            "ANTHROPIC",
                            1,
                            model,
                            batch,
                            ObjectMappers.jsonMapper().writeValueAsString(segment),
                            result.size()));
        return result;
    }

    private @NonNull String responsePrompt(@NonNull VetoRequest request) {
        return NativeToolResponses.prompt(prompts, "provider-native", request);
    }

    private boolean permitsNativeCalls(@NonNull VetoRequest request) {
        return request.nativeToolsEnabled() && !request.tools().isEmpty();
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
        Tool.InputSchema.Builder builder = Tool.InputSchema.builder();
        if (inputSchema.containsKey("properties")) builder.properties(toolProperties(inputSchema));
        // The SDK models type/properties directly. Preserve every other canonical keyword,
        // including root-level definitions/composition from remote tools, without rewriting it.
        inputSchema.forEach(
                (key, value) -> {
                    if (!key.equals("properties") && !key.equals("type"))
                        builder.putAdditionalProperty(key, JsonValue.from(value));
                });
        return builder.build();
    }

    /**
     * Maps the compiled conversation to Anthropic message params, merging consecutive same-role
     * messages (the API requires strict role alternation). System messages are dropped - the
     * request's system prompt rides in {@code system(...)}. Tool results become user-role {@code
     * tool_result} blocks keyed by tool_use_id; synthetic observations (null callId) become plain
     * user text, matching the compiler's intent.
     */
    private @NonNull List<MessageParam> toMessageParams(@NonNull VetoRequest request)
            throws Exception {
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
            var headState = group.getFirst().nativeState();
            boolean replayNative = headState != null && headState.position() == 0;
            for (ChatMessage m : group) {

                var state = m.nativeState();
                if (replayNative
                        && state != null
                        && state.supports("ANTHROPIC", request.modelName())) {
                    List<ContentBlockParam> original =
                            ObjectMappers.jsonMapper()
                                    .readValue(
                                            state.partsJson(),
                                            new TypeReference<List<ContentBlockParam>>() {});
                    groupBlocks.addAll(original);
                    continue;
                }

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
