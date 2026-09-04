package top.focess.veto.agent.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
public final class ToolCallContextHolder {

    private static final @NonNull ConcurrentMap<Thread, ThreadState> STATES =
            new ConcurrentHashMap<>();

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

    private ToolCallContextHolder() {}

    /** Sets the tool call context for the current thread. */
    public static void set(@NonNull ToolCallContext ctx) {
        state().context = ctx;
    }

    /**
     * Gets the tool call context for the current thread.
     *
     * @return the context, or {@code null} if not set (e.g. when called outside AgentRunner's
     *     execute scope)
     */
    public static ToolCallContext get() {
        ThreadState state = currentState();
        return state == null ? null : state.context;
    }

    static void setCurrentCallId(@NonNull String callId) {
        state().currentCallId = callId;
    }

    public static String currentCallId() {
        ThreadState state = currentState();
        return state == null ? null : state.currentCallId;
    }

    /**
     * Requests a REWIND directive be appended to history after the current tool call returns. The
     * {@code fromIndex} suffix-drops the compiled view (keeping the seed turns, e.g. {@code 1} to
     * keep AGENT_INIT), and {@code content} is re-injected as a user message - seeding the
     * delegating agent with the supplied brief.
     */
    public static void requestRewind(int fromIndex, @NonNull String content) {
        state().pendingTurns.add(TurnRecord.rewind(0, fromIndex, content));
    }

    /**
     * Requests a forward transform (STANDALONE -> Leader) be applied after the current tool call
     * returns. The runner drains and applies it in the same tool-call drain pass: it appends the
     * transform turn sequence (REWIND + AGENT_INIT + COMPACTION_SUMMARY + USER_PROMPT) and mutates
     * the persona / binding / group. Supersedes any pending rewind - a transform is the stronger
     * rewrite.
     */
    public static void requestTransform(@NonNull TransformDirective directive) {
        state().transform = new TransformRequest.ToLeader(directive);
    }

    /**
     * Requests a reverse transform (Leader -> STANDALONE) be applied after the current tool call
     * returns - the group was disbanded. The runner rewinds, restores the stashed STANDALONE
     * persona + binding, and re-injects {@code brief} (the group's outcome) so the agent continues
     * autonomously.
     */
    public static void requestReverseTransform(@NonNull String brief) {
        state().transform = new TransformRequest.ToStandalone(brief);
    }

    /**
     * Drains and clears the transform request for the current thread, if one was requested. Called
     * by {@link AgentRunner} after a tool call returns and after pending turn directives are
     * drained.
     *
     * @return the request, or {@code null} if the tool requested no transform
     */
    public static TransformRequest drainTransform() {
        ThreadState state = currentState();
        TransformRequest request = state == null ? null : state.transform;
        if (state != null) state.transform = null;
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
        ThreadState state = currentState();
        if (state == null || state.pendingTurns.isEmpty()) {
            return List.of();
        }
        List<TurnRecord> copy = new ArrayList<>(state.pendingTurns);
        state.pendingTurns.clear();
        return copy;
    }

    private static @NonNull ThreadState state() {
        return STATES.computeIfAbsent(Thread.currentThread(), ignored -> new ThreadState());
    }

    private static ThreadState currentState() {
        return STATES.get(Thread.currentThread());
    }

    /** Clears the tool call context (and any pending turn directives) for the current thread. */
    public static void clear() {
        STATES.remove(Thread.currentThread());
    }

    private static final class ThreadState {
        private ToolCallContext context;
        private String currentCallId;
        private final @NonNull List<@NonNull TurnRecord> pendingTurns = new ArrayList<>();
        private TransformRequest transform;
    }
}
