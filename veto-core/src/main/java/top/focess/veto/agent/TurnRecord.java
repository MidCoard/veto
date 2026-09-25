package top.focess.veto.agent;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.ToolErrorCode;
import top.focess.veto.api.agent.tool.ToolResult;
import top.focess.veto.api.agent.tool.ToolResultFormat;
import top.focess.veto.api.agent.tool.ToolResultStatus;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.ToolResultPresentationMode;

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
        Instant timestamp,
        java.util.@NonNull List<UsageMeasurement> llmUsage) {

    /**
     * Convenience constructor that defaults a null timestamp to now and decodes any usage carried
     * in the payload.
     */
    public TurnRecord(
            int turnNumber,
            @NonNull TurnType type,
            @NonNull Map<String, Object> payload,
            Instant timestamp) {
        this(
                turnNumber,
                type,
                payload,
                timestamp == null ? Instant.now() : timestamp,
                RecordUsage.decode(payload.get("llmUsage")));
    }

    /**
     * Canonical constructor: copies the usage list, strips usage keys from an unmodifiable
     * null-tolerant payload copy, and normalizes a missing timestamp before publication.
     */
    public TurnRecord {
        llmUsage = java.util.List.copyOf(llmUsage);
        var content = new LinkedHashMap<>(payload);
        content.remove("llmUsage");
        content.remove("usageCheckpoint");
        payload = content;
        // Null-tolerant unmodifiable copy: the payload schema has OPTIONAL fields (e.g. a
        // synthetic TOOL_RESPONSE observation carries no call_id), so Map.copyOf's null-hostile
        // copy would throw NPE for those. Keys remain String; values may legitimately be null.
        payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
        if (timestamp == null) {
            timestamp = Instant.now();
        }
    }

    /** The canonical constructor normalizes a missing timestamp before publication. */
    @Override
    public @NonNull Instant timestamp() {
        Instant normalized = timestamp;
        if (normalized == null) {
            throw new IllegalStateException("Turn timestamp was not normalized");
        }
        return normalized;
    }

    // ── Factories ───────────────────────────────────────────────────────────

    /**
     * A copy of this turn with its number replaced. The runner uses this when it authoritatively
     * assigns the durable turn number on append (see {@code AgentRunner.appendTurn}); turns are
     * otherwise immutable.
     */
    public @NonNull TurnRecord withTurnNumber(int turnNumber) {
        return new TurnRecord(turnNumber, type, payload, timestamp(), llmUsage);
    }

    /** A user prompt ({@code payload.content}). */
    public static @NonNull TurnRecord userPrompt(int turnNumber, @NonNull String content) {
        return new TurnRecord(turnNumber, TurnType.USER_PROMPT, Map.of("content", content), null);
    }

    /**
     * A literal {@code continue} entered after a breaker trip. {@code content} preserves exactly
     * what the user typed for audit/UI; {@code resume_context} lets the prompt compiler render a
     * self-contained continuation request even when token budgeting trimmed the earlier task.
     */
    public static @NonNull TurnRecord breakerContinuation(
            int turnNumber, @NonNull String content, @NonNull String resumeContext) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", content);
        payload.put("resume_context", resumeContext);
        return new TurnRecord(turnNumber, TurnType.USER_PROMPT, payload, null);
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
        var nativeState = call.nativeState();
        if (nativeState != null) p.put("native_state", nativeState.toPayload());
        return new TurnRecord(turnNumber, TurnType.TOOL_CALL, p, null);
    }

    /**
     * The framed observation returned for a tool call ({@code payload.call_id/content/success}).
     */
    public static @NonNull TurnRecord toolResponse(
            int turnNumber, String callId, @NonNull String content, boolean success) {
        return toolResponse(
                turnNumber,
                callId,
                success ? ToolResultStatus.SUCCESS : ToolResultStatus.FAILURE,
                ToolResultFormat.UNKNOWN,
                content,
                success ? null : ToolErrorCode.GENERIC.TOOL_FAILURE);
    }

    /** A tool response built from a structured {@link ToolResult}. */
    public static @NonNull TurnRecord toolResponse(int turnNumber, @NonNull ToolResult result) {
        return toolResponse(
                turnNumber,
                result.callId(),
                result.status(),
                result.format(),
                result.content(),
                result.errorCode());
    }

    /**
     * A tool response whose {@code content} is already the exact model-visible representation for
     * this session. The separate status fields retain structured data for transports, the records
     * UI, and usage projections; replay must not transform {@code content} again.
     */
    public static @NonNull TurnRecord presentedToolResponse(
            int turnNumber,
            @NonNull ToolResult result,
            @NonNull String presentedContent,
            @NonNull ToolResultPresentationMode presentation) {
        Map<String, Object> payload =
                toolResponsePayload(
                        result.callId(),
                        result.status(),
                        result.format(),
                        presentedContent,
                        result.errorCode());
        payload.put("presentation", presentation.name());
        return new TurnRecord(turnNumber, TurnType.TOOL_RESPONSE, payload, null);
    }

    /** A tool response with explicit status, format and error code; {@code callId} may be null. */
    public static @NonNull TurnRecord toolResponse(
            int turnNumber,
            String callId,
            @NonNull ToolResultStatus status,
            @NonNull ToolResultFormat format,
            @NonNull String content,
            ToolErrorCode errorCode) {
        return new TurnRecord(
                turnNumber,
                TurnType.TOOL_RESPONSE,
                toolResponsePayload(callId, status, format, content, errorCode),
                null);
    }

    private static @NonNull Map<String, Object> toolResponsePayload(
            String callId,
            @NonNull ToolResultStatus status,
            @NonNull ToolResultFormat format,
            @NonNull String content,
            ToolErrorCode errorCode) {
        // callId is OPTIONAL (absent for synthetic observations — plan-escape, llm-error,
        // tool-not-found), so Map.of's null-hostile builder would throw; use a null-tolerant map.
        Map<String, Object> p = new LinkedHashMap<>();
        if (callId != null) {
            p.put("call_id", callId);
        }
        p.put("content", content);
        p.put("success", status == ToolResultStatus.SUCCESS);
        p.put("status", status.id());
        p.put("format", format.id());
        if (errorCode != null) {
            // The payload is the persistence/wire shape: the code travels as its enum name.
            p.put("errorCode", errorCode.name());
        }
        return p;
    }

    /**
     * A rewind directive — 0-based suffix-drop: keeps compiled positions {@code 0..from_index-1},
     * drops {@code from_index..end}. Never emitted as a message.
     */
    public static @NonNull TurnRecord rewind(int turnNumber, int fromIndex) {
        return new TurnRecord(
                turnNumber,
                TurnType.REWIND,
                Map.of(fromIndex == 0 ? "record_index" : "from_index", fromIndex),
                null);
    }

    /** A rewind that also re-injects a recalled brief as the next user message. */
    public static @NonNull TurnRecord rewind(
            int turnNumber, int fromIndex, @NonNull String content) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("from_index", fromIndex);
        payload.put("content", content);
        return new TurnRecord(turnNumber, TurnType.REWIND, payload, null);
    }

    /** A compaction summary seed re-injected after a {@link #rewind}. */
    public static @NonNull TurnRecord compactionSummary(int turnNumber, @NonNull String content) {
        return new TurnRecord(
                turnNumber, TurnType.COMPACTION_SUMMARY, Map.of("content", content), null);
    }

    /**
     * An ordered system-prompt insertion (session start or an actual role transformation). The
     * snapshot records the instructions used when this agent was initialized. Current requests link
     * the current runtime template and session settings; a historical snapshot cannot override
     * capability settings after a restart.
     */
    public static @NonNull TurnRecord agentInit(
            int turnNumber,
            @NonNull String role,
            @NonNull String systemPrompt,
            @NonNull String provider,
            @NonNull String model) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("role", role);
        payload.put("system_prompt", systemPrompt);
        payload.put("provider", provider);
        payload.put("model", model);
        return new TurnRecord(turnNumber, TurnType.AGENT_INIT, payload, null);
    }
}
