package top.focess.veto.agent;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import top.focess.veto.llm.core.ToolCall;

/**
 * One durable event in the agent's turn history — the append-only raw history the {@code
 * PromptCompiler} walks to assemble each outgoing LLM payload (LLD {@code prompt_compiler.md} §3).
 *
 * <p>The {@link #payload} carries the type-specific fields the compiler reads to map the turn to an
 * API message role (§3.2). {@code REWIND} is a compiler directive, never emitted as a message.
 *
 * <p>Immutable. The loop appends; the compiled view is rebuilt fresh each cycle from the raw list.
 */
public record TurnRecord(
        int turnNumber, TurnType type, Map<String, Object> payload, Instant timestamp) {

    public TurnRecord {
        if (type == null) {
            throw new IllegalArgumentException("type");
        }
        if (payload == null) {
            payload = Map.of();
        } else {
            // Null-tolerant unmodifiable copy: the payload schema has OPTIONAL fields (e.g. a
            // synthetic TOOL_RESPONSE observation carries no call_id), so Map.copyOf's null-hostile
            // copy would throw NPE for those. Keys remain String; values may legitimately be null.
            payload = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(payload));
        }
        if (timestamp == null) {
            timestamp = Instant.now();
        }
    }

    // ── Factories ───────────────────────────────────────────────────────────

    /** A user prompt ({@code payload.content}). */
    public static TurnRecord userPrompt(int turnNumber, String content) {
        return new TurnRecord(turnNumber, TurnType.USER_PROMPT, Map.of("content", content), null);
    }

    /** A user interrupt/feedback ({@code payload.feedback}). */
    public static TurnRecord userInterrupt(int turnNumber, String feedback) {
        return new TurnRecord(
                turnNumber, TurnType.USER_INTERRUPT, Map.of("feedback", feedback), null);
    }

    /**
     * The raw {@code VetoResponse} JSON string for a thought-ON turn ({@code payload.response}).
     */
    public static TurnRecord assistantThought(int turnNumber, String rawResponseJson) {
        return new TurnRecord(
                turnNumber, TurnType.ASSISTANT_THOUGHT, Map.of("response", rawResponseJson), null);
    }

    /** A user-facing message the agent emitted ({@code payload.content}). */
    public static TurnRecord assistantResponse(int turnNumber, String content) {
        return new TurnRecord(
                turnNumber, TurnType.ASSISTANT_RESPONSE, Map.of("content", content), null);
    }

    /** A tool call the agent issued ({@code payload.call_id/tool_name/args}). */
    public static TurnRecord toolCall(int turnNumber, ToolCall call) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("call_id", call.callId());
        p.put("tool_name", call.toolName());
        p.put("args", call.args());
        return new TurnRecord(turnNumber, TurnType.TOOL_CALL, p, null);
    }

    /**
     * The framed observation returned for a tool call ({@code payload.call_id/content/success}).
     */
    public static TurnRecord toolResponse(
            int turnNumber, String callId, String content, boolean success) {
        // callId is OPTIONAL (absent for synthetic observations — guided-escape, llm-error,
        // tool-not-found), so Map.of's null-hostile builder would throw; use a null-tolerant map.
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("call_id", callId);
        p.put("content", content);
        p.put("success", success);
        return new TurnRecord(turnNumber, TurnType.TOOL_RESPONSE, p, null);
    }

    /**
     * A rewind directive — 0-based suffix-drop: keeps compiled positions {@code 0..from_index-1},
     * drops {@code from_index..end}. Never emitted as a message.
     */
    public static TurnRecord rewind(int turnNumber, int fromIndex) {
        return new TurnRecord(turnNumber, TurnType.REWIND, Map.of("from_index", fromIndex), null);
    }

    /** A compaction summary seed re-injected after a {@link #rewind}. */
    public static TurnRecord compactionSummary(int turnNumber, String content) {
        return new TurnRecord(
                turnNumber, TurnType.COMPACTION_SUMMARY, Map.of("content", content), null);
    }
}
