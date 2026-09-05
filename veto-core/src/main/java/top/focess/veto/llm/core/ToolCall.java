package top.focess.veto.llm.core;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.node.NullNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * A single tool call from the LLM. Each element of {@link VetoResponse#calls()} is a {@code
 * ToolCall}.
 *
 * <p>The model emits {@code tool_name} + {@code args} (the {@code calls[]} item schema has no
 * {@code call_id}). {@code callId} is <b>harness-assigned</b> during construction — it pairs the
 * call with the returned {@code ToolResult.callId} and the HITL future keyed by {@code (agentId,
 * callId)}. It is absent from the model-facing schema; the two-argument convenience constructor
 * assigns it immediately, including when JSON omits it.
 *
 * <p>The {@code @JsonProperty} mappings are mandatory: the schema uses snake_case ({@code
 * tool_name}), so without them Jackson would silently bind {@code null}.
 *
 * @param toolName the name of the tool being called
 * @param args the arguments for the tool call
 * @param callId harness-assigned id for result/HITL pairing (not model-emitted)
 */
public record ToolCall(
        @JsonProperty("tool_name") @NonNull String toolName,
        @JsonProperty("args") @NonNull Map<@NonNull String, Object> args,
        @JsonProperty("call_id") @NonNull String callId) {

    public ToolCall(
            @NonNull String toolName, @NonNull Map<@NonNull String, Object> args, String callId) {
        this.toolName = toolName;
        this.args = immutableMap(args);
        this.callId =
                callId == null ? "call_" + UUID.randomUUID().toString().substring(0, 8) : callId;
    }

    /** Creates a call with a harness-assigned id. */
    public ToolCall(@NonNull String toolName, @NonNull Map<@NonNull String, Object> args) {
        this(toolName, args, null);
    }

    private static @NonNull Map<@NonNull String, Object> immutableMap(
            @NonNull Map<@NonNull String, Object> source) {
        Map<@NonNull String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, immutableValue(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static @NonNull Object immutableValue(Object value) {
        if (value == null) {
            return NullNode.getInstance();
        }
        if (value instanceof Map<?, ?> nested) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            for (var entry : nested.entrySet()) {
                Object key = entry.getKey();
                if (key == null) {
                    throw new IllegalArgumentException("JSON object key must not be null");
                }
                copy.put(key, immutableValue(entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object child : list) {
                copy.add(immutableValue(child));
            }
            return Collections.unmodifiableList(copy);
        }
        return value;
    }
}
