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
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.veto.llm.core.ChatMessage;
import top.focess.veto.llm.core.ResolvedRequest;
import top.focess.veto.llm.core.ToolDefinition;
import top.focess.veto.llm.core.VetoRequest;
import top.focess.veto.llm.exceptions.ModelCapabilityException;

/**
 * Adapter wrapping an {@link AnthropicClient} — <b>native tool calling</b>, the way Claude Code
 * drives Anthropic-protocol endpoints: the tool manifest is registered as native tools (the
 * provider schema-checks every call), the compiled history maps to native assistant {@code
 * tool_use} / user {@code tool_result} blocks, and the response's tool_use blocks translate back
 * into a veto_pulse payload ({@code calls} from the blocks, text becoming {@code thought} or {@code
 * message}, {@code features.guided=false} synthesized - guided mode has no native expression).
 *
 * <p>This supersedes the original forced-single-{@code veto_pulse}-tool design: that required the
 * endpoint to honor {@code tool_choice: forced}, which Anthropic-compatible third parties (MiniMax
 * & co) silently ignore, and it sent only the last user message, so the model looped blindly.
 * Native mode works on both real Anthropic models and the compatible clones.
 *
 * <p>All Anthropic SDK types are confined to this class.
 */
final class AnthropicLlmClient extends LlmClient {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(AnthropicLlmClient.class);

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
                        .system(request.systemPrompt());
        if (request.options().temperature() != null) {
            builder.temperature(request.options().temperature());
        }

        // The manifest as native tools; no forced tool_choice (the clones ignore it anyway).
        for (ToolDefinition t : request.tools()) {
            Object properties = t.inputSchema().get("properties");
            builder.addTool(
                    Tool.builder()
                            .name(t.name())
                            .description(t.description())
                            .inputSchema(
                                    Tool.InputSchema.builder()
                                            .properties(
                                                    JsonValue.from(
                                                            properties == null
                                                                    ? Map.of()
                                                                    : properties))
                                            .build())
                            .build());
        }

        for (MessageParam messageParam : toMessageParams(request)) {
            builder.addMessage(messageParam);
        }

        Message message = sdkClient.messages().create(builder.build());
        if (message.usage() != null) {
            top.focess.veto.llm.core.LlmSystemUsage.set(
                    message.usage().inputTokens(), message.usage().outputTokens());
        }
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
                        .collect(java.util.stream.Collectors.joining("\n"))
                        .strip();

        String rawInput;
        if (!toolUses.isEmpty()) {
            var pulse = objectMapper.createObjectNode();
            var calls = pulse.putArray("calls");
            for (ToolUseBlock tu : toolUses) {
                var call = calls.addObject();
                call.put("tool_name", tu.name());
                call.set("args", objectMapper.valueToTree(toolInputMap(tu)));
            }
            if (!text.isEmpty()) {
                pulse.put("thought", text);
            }
            pulse.putObject("features").put("guided", false);
            rawInput = pulse.toString();
        } else {
            if (text.isEmpty()) {
                throw new ModelCapabilityException(
                        "Anthropic response contained neither text nor tool calls");
            }
            // Text-only answer. A model following the system prompt's veto_pulse instructions may
            // still emit the pulse JSON as text - honor it when it parses as a pulse object;
            // otherwise the prose IS the final message.
            String candidate = extractJson(objectMapper, text);
            if (isPulseJson(candidate)) {
                rawInput = candidate;
            } else {
                var pulse = objectMapper.createObjectNode();
                pulse.put("message", text);
                pulse.putObject("features").put("guided", false);
                rawInput = pulse.toString();
            }
        }

        String summary = "model=" + request.modelName() + ", tools=" + request.tools().size();
        return new RawCompletion(summary, rawInput);
    }

    /**
     * Maps the compiled conversation to Anthropic message params, merging consecutive same-role
     * messages (the API requires strict role alternation). System messages are dropped - the
     * request's system prompt rides in {@code system(...)}. Tool results become user-role {@code
     * tool_result} blocks keyed by tool_use_id; synthetic observations (null callId) become plain
     * user text, matching the compiler's intent.
     */
    private @NonNull List<MessageParam> toMessageParams(@NonNull VetoRequest request) {
        List<ChatMessage> history = request.messages();
        if (history.isEmpty()) {
            return List.of(
                    MessageParam.builder()
                            .role(MessageParam.Role.USER)
                            .content(request.userPrompt())
                            .build());
        }
        List<MessageParam> out = new ArrayList<>();
        MessageParam.Role pendingRole = null;
        List<ContentBlockParam> pendingBlocks = new ArrayList<>();
        for (ChatMessage m : history) {
            MessageParam.Role role;
            List<ContentBlockParam> blocks = new ArrayList<>();
            switch (m.role()) {
                case "system" -> {
                    continue;
                }
                case "assistant" -> {
                    role = MessageParam.Role.ASSISTANT;
                    if (!m.content().isEmpty()) {
                        blocks.add(
                                ContentBlockParam.ofText(
                                        TextBlockParam.builder().text(m.content()).build()));
                    }
                    if (m.toolName() != null && m.callId() != null) {
                        blocks.add(
                                ContentBlockParam.ofToolUse(
                                        ToolUseBlockParam.builder()
                                                .id(m.callId())
                                                .name(m.toolName())
                                                .input(toolInputParam(m.toolArgs()))
                                                .build()));
                    }
                }
                case "tool" -> {
                    role = MessageParam.Role.USER;
                    if (m.callId() != null && !m.callId().isBlank()) {
                        blocks.add(
                                ContentBlockParam.ofToolResult(
                                        ToolResultBlockParam.builder()
                                                .toolUseId(m.callId())
                                                .content(m.content())
                                                .build()));
                    } else {
                        blocks.add(
                                ContentBlockParam.ofText(
                                        TextBlockParam.builder().text(m.content()).build()));
                    }
                }
                default -> {
                    role = MessageParam.Role.USER;
                    blocks.add(
                            ContentBlockParam.ofText(
                                    TextBlockParam.builder().text(m.content()).build()));
                }
            }
            if (blocks.isEmpty()) {
                continue;
            }
            if (pendingRole == role) {
                pendingBlocks.addAll(blocks);
            } else {
                if (pendingRole != null) {
                    out.add(buildParam(pendingRole, pendingBlocks));
                }
                pendingRole = role;
                pendingBlocks = blocks;
            }
        }
        if (pendingRole != null) {
            out.add(buildParam(pendingRole, pendingBlocks));
        }
        return out;
    }

    private static @NonNull MessageParam buildParam(
            MessageParam.@NonNull Role role, @NonNull List<ContentBlockParam> blocks) {
        return MessageParam.builder().role(role).contentOfBlockParams(blocks).build();
    }

    /** Parses a tool-call args JSON string into the SDK's input param; empty input on bad JSON. */
    private @NonNull ToolUseBlockParam.Input toolInputParam(@Nullable String toolArgs) {
        ToolUseBlockParam.Input.Builder input = ToolUseBlockParam.Input.builder();
        if (toolArgs != null && !toolArgs.isBlank()) {
            try {
                Map<String, Object> args =
                        objectMapper.readValue(toolArgs, new TypeReference<>() {});
                args.forEach((k, v) -> input.putAdditionalProperty(k, JsonValue.from(v)));
            } catch (Exception e) {
                log.debug("AnthropicLlmClient: unparseable tool args, sending empty input: {}", toolArgs);
            }
        }
        return input.build();
    }

    /** A response tool_use block's input as a plain map (never the Java-Map {@code toString()}). */
    private @NonNull Map<String, Object> toolInputMap(@NonNull ToolUseBlock tu) {
        try {
            return tu._input().convert(new TypeReference<>() {});
        } catch (Exception e) {
            throw new ModelCapabilityException(
                    "Anthropic tool input was not a JSON object: " + e.getMessage());
        }
    }

    /** True when the text parses as a JSON object carrying any veto_pulse field. */
    private boolean isPulseJson(@NonNull String candidate) {
        try {
            var node = objectMapper.readTree(candidate);
            return node.isObject()
                    && (node.has("calls")
                            || node.has("message")
                            || node.has("thought")
                            || node.has("features")
                            || node.has("is_finished"));
        } catch (Exception e) {
            return false;
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
