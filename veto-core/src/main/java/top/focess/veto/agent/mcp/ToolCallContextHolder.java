package top.focess.veto.agent.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.AgentRunner;
import top.focess.veto.agent.TurnRecord;

/**
 * Thread-local holder for {@link ToolCallContext}. {@link AgentRunner} sets the context before
 * calling {@link ToolEngine#execute}, and {@link NativeTool} / {@link AgentTool} implementations
 * read it during execution. This avoids changing the tool interface contracts.
 *
 * <p><b>Usage in AgentRunner:</b>
 *
 * <pre>{@code
 * ToolCallContextHolder.set(context);
 * try {
 *     ToolResult result = toolEngine.execute(call, def);
 * } finally {
 *     ToolCallContextHolder.clear();
 * }
 * }</pre>
 *
 * <p><b>Usage in a tool:</b>
 *
 * <pre>{@code
 * ToolCallContext ctx = ToolCallContextHolder.get();
 * String callerId = ctx != null ? ctx.agentId() : "unknown";
 * }</pre>
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class ToolCallContextHolder {

    // ThreadLocal.get() is intrinsically nullable. Keep its element type nullable-by-default and
    // refine with instanceof at the access boundary instead of pretending the slot is non-null.
    private static final @NonNull ThreadLocal CONTEXT = new ThreadLocal();

    /**
     * Pending rewind directives a tool requested during its execution. {@link AgentRunner} owns the
     * monotonic turn counter, so the placeholder {@code turnNumber} on each pending record is
     * rewritten when the runner drains and appends them (type + payload are preserved). The runner
     * drains on the same thread that executed the tool, so the ThreadLocal is visible.
     */
    private static final @NonNull ThreadLocal PENDING_TURNS =
            ThreadLocal.withInitial(ArrayList::new);

    /**
     * A transform-to-Leader directive requested by {@code create_group} during its execution. The
     * runner owns the turn counter + history, so it computes the compaction summary and appends the
     * REWIND/AGENT_INIT/COMPACTION_SUMMARY/USER_PROMPT sequence itself; this directive carries only
     * what the tool resolves (the brief, the registered group id, the Leader binding + Leader tool
     * set). At most one per tool call - a transform supersedes any pending rewind.
     */
    public record TransformDirective(
            @NonNull String brief,
            @NonNull UUID groupId,
            AgentRunner.@NonNull LlmBinding leaderBinding,
            @NonNull Set<ToolDefinition> leaderTools) {}

    /**
     * A delegation transform request: either the forward transform (STANDALONE -> Leader of a new
     * group, requested by {@code create_group}) or the reverse (Leader -> STANDALONE, requested by
     * {@code disband_group}). The runner drains and applies it in the same tool-call drain pass. At
     * most one per tool call - the last request wins.
     */
    public sealed interface TransformRequest {
        /** Transform the calling STANDALONE into the Leader of a new group. */
        record ToLeader(@NonNull TransformDirective directive) implements TransformRequest {}

        /** Reverse the transform: the Leader becomes STANDALONE again (the group was disbanded). */
        record ToStandalone(@NonNull String brief) implements TransformRequest {}
    }

    private static final @NonNull ThreadLocal PENDING_TRANSFORM = new ThreadLocal();

    private ToolCallContextHolder() {}

    /** Sets the tool call context for the current thread. */
    public static void set(@NonNull ToolCallContext ctx) {
        CONTEXT.set(ctx);
    }

    /**
     * Gets the tool call context for the current thread.
     *
     * @return the context, or {@code null} if not set (e.g. when called outside AgentRunner's
     *     execute scope)
     */
    public static ToolCallContext get() {
        Object value = CONTEXT.get();
        return value instanceof ToolCallContext context ? context : null;
    }

    /**
     * Requests a REWIND directive be appended to history after the current tool call returns. The
     * {@code fromIndex} suffix-drops the compiled view (keeping the seed turns, e.g. {@code 1} to
     * keep AGENT_INIT), and {@code content} is re-injected as a user message - seeding the
     * delegating agent with the supplied brief.
     */
    public static void requestRewind(int fromIndex, @NonNull String content) {
        pendingTurns().add(TurnRecord.rewind(0, fromIndex, content));
    }

    /**
     * Requests a forward transform (STANDALONE -> Leader) be applied after the current tool call
     * returns. The runner drains and applies it in the same tool-call drain pass: it appends the
     * transform turn sequence (REWIND + AGENT_INIT + COMPACTION_SUMMARY + USER_PROMPT) and mutates
     * the persona / binding / group. Supersedes any pending rewind - a transform is the stronger
     * rewrite.
     */
    public static void requestTransform(@NonNull TransformDirective directive) {
        PENDING_TRANSFORM.set(new TransformRequest.ToLeader(directive));
    }

    /**
     * Requests a reverse transform (Leader -> STANDALONE) be applied after the current tool call
     * returns - the group was disbanded. The runner rewinds, restores the stashed STANDALONE
     * persona + binding, and re-injects {@code brief} (the group's outcome) so the agent continues
     * autonomously.
     */
    public static void requestReverseTransform(@NonNull String brief) {
        PENDING_TRANSFORM.set(new TransformRequest.ToStandalone(brief));
    }

    /**
     * Drains and clears the transform request for the current thread, if one was requested. Called
     * by {@link AgentRunner} after a tool call returns and after pending turn directives are
     * drained.
     *
     * @return the request, or {@code null} if the tool requested no transform
     */
    public static TransformRequest drainTransform() {
        Object value = PENDING_TRANSFORM.get();
        TransformRequest request = value instanceof TransformRequest transform ? transform : null;
        if (request != null) {
            PENDING_TRANSFORM.remove();
        }
        return request;
    }

    /**
     * Drains and clears the pending turn directives for the current thread. Called by {@link
     * AgentRunner} after a tool call returns; each entry is appended with a runner-assigned turn
     * number.
     *
     * @return the pending directives (empty if none); never null
     */
    public static @NonNull List<@NonNull TurnRecord> drainPendingTurns() {
        List<@NonNull TurnRecord> turns = pendingTurns();
        if (turns.isEmpty()) {
            return List.of();
        }
        List<TurnRecord> copy = new ArrayList<>(turns);
        turns.clear();
        return copy;
    }

    private static @NonNull List<@NonNull TurnRecord> pendingTurns() {
        Object value = PENDING_TURNS.get();
        if (value instanceof List<?>) {
            return (List<@NonNull TurnRecord>) value;
        }
        List<@NonNull TurnRecord> turns = new ArrayList<>();
        PENDING_TURNS.set(turns);
        return turns;
    }

    /** Clears the tool call context (and any pending turn directives) for the current thread. */
    public static void clear() {
        CONTEXT.remove();
        PENDING_TURNS.remove();
        PENDING_TRANSFORM.remove();
    }
}
