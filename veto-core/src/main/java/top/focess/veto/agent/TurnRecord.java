package top.focess.veto.agent;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.veto.llm.core.ToolCall;

/**
 * One durable event in the agent's turn history — the append-only raw history the {@code
 * PromptCompiler} walks to assemble each outgoing LLM payload.
 *
 * <p>The {@link #payload} carries the type-specific fields the compiler reads to map the turn to an
 * API message role. {@code REWIND} is a compiler directive, never emitted as a message.
 *
 * <p>Immutable. The loop appends; the compiled view is rebuilt fresh each cycle from the raw list.
 */
public record TurnRecord(
        int turnNumber,
        @NonNull TurnType type,
        @NonNull Map<String, Object> payload,
        @Nullable Instant timestamp) {

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
    public static @NonNull TurnRecord userPrompt(int turnNumber, @NonNull String content) {
        return new TurnRecord(turnNumber, TurnType.USER_PROMPT, Map.of("content", content), null);
    }

    /** A user interrupt/feedback ({@code payload.feedback}). */
    public static @NonNull TurnRecord userInterrupt(int turnNumber, @NonNull String feedback) {
        return new TurnRecord(
                turnNumber, TurnType.USER_INTERRUPT, Map.of("feedback", feedback), null);
    }

    /**
     * The raw {@code VetoResponse} JSON string for a thought-ON turn ({@code payload.response}).
     */
    public static @NonNull TurnRecord assistantThought(
            int turnNumber, @NonNull String rawResponseJson) {
        return new TurnRecord(
                turnNumber, TurnType.ASSISTANT_THOUGHT, Map.of("response", rawResponseJson), null);
    }

    /** A user-facing message the agent emitted ({@code payload.content}). */
    public static @NonNull TurnRecord assistantResponse(int turnNumber, @NonNull String content) {
        return new TurnRecord(
                turnNumber, TurnType.ASSISTANT_RESPONSE, Map.of("content", content), null);
    }

    /** A tool call the agent issued ({@code payload.call_id/tool_name/args}). */
    public static @NonNull TurnRecord toolCall(int turnNumber, @NonNull ToolCall call) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("call_id", call.callId());
        p.put("tool_name", call.toolName());
        p.put("args", call.args());
        return new TurnRecord(turnNumber, TurnType.TOOL_CALL, p, null);
    }

    /**
     * The framed observation returned for a tool call ({@code payload.call_id/content/success}).
     */
    public static @NonNull TurnRecord toolResponse(
            int turnNumber, @Nullable String callId, @NonNull String content, boolean success) {
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
    public static @NonNull TurnRecord rewind(int turnNumber, int fromIndex) {
        return new TurnRecord(turnNumber, TurnType.REWIND, Map.of("from_index", fromIndex), null);
    }

    /** A compaction summary seed re-injected after a {@link #rewind}. */
    public static @NonNull TurnRecord compactionSummary(int turnNumber, @NonNull String content) {
        return new TurnRecord(
                turnNumber, TurnType.COMPACTION_SUMMARY, Map.of("content", content), null);
    }

    /**
     * A role-start marker (session start, delegation transform). Delimits a role-segment: {@link
     * top.focess.veto.agent.loop.PromptCompiler} maps it to no message (the front system message
     * already carries the role), and compaction uses it as the anchor for the current segment. The
     * {@code content} is a label kept for the audit record.
     */
    public static @NonNull TurnRecord agentInit(int turnNumber, @NonNull String content) {
        return new TurnRecord(turnNumber, TurnType.AGENT_INIT, Map.of("content", content), null);
    }

    /**
     * A recall directive - a composite of a suffix-drop + a re-injected brief. The compiler drops
     * compiled positions {@code from_index..end} (keeping {@code 0..from_index-1}, typically the
     * AGENT_INIT seed), then appends {@code content} as a user message. Never emitted as an
     * assistant message.
     */
    public static @NonNull TurnRecord recall(
            int turnNumber, int fromIndex, @NonNull String content) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("from_index", fromIndex);
        p.put("content", content);
        return new TurnRecord(turnNumber, TurnType.RECALL, p, null);
    }
}
