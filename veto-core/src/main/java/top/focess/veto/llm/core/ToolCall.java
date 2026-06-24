package top.focess.veto.llm.core;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * A single tool call from the LLM. Each element of {@link VetoResponse#calls} is a {@code
 * ToolCall}.
 *
 * <p>The model emits {@code tool_name} + {@code args} (the {@code calls[]} item schema has no
 * {@code call_id}). {@code callId} is <b>harness-assigned</b> after parsing — it pairs the call
 * with the returned {@code McpToolResult.callId} and the HITL future keyed by {@code (agentId,
 * callId)}. It is absent from the model-facing schema; the two-argument convenience constructor
 * leaves it null for the harness to assign.
 *
 * <p>The {@code @JsonProperty} mappings are mandatory: the schema uses snake_case ({@code
 * tool_name}), so without them Jackson would silently bind {@code null}.
 *
 * @param toolName the name of the tool being called
 * @param args the arguments for the tool call
 * @param callId harness-assigned id for result/HITL pairing (not model-emitted)
 */
public record ToolCall(
        @JsonProperty("tool_name") String toolName,
        @JsonProperty("args") Map<String, Object> args,
        @JsonProperty("call_id") String callId) {

    /** Convenience constructor for model-parsed calls (callId assigned later by the harness). */
    public ToolCall(String toolName, Map<String, Object> args) {
        this(toolName, args, null);
    }
}
