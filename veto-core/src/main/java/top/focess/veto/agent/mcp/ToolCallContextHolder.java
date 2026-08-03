package top.focess.veto.agent.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
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
 * ToolCallContextHolder.set(agentId, userId);
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

    private static final ThreadLocal<ToolCallContext> CONTEXT = new ThreadLocal<>();

    /**
     * Pending turn directives a tool requested during its execution (e.g. {@code create_group}
     * requests a RECALL to seed the delegating agent with the authored brief). {@link AgentRunner}
     * owns the monotonic turn counter, so the placeholder {@code turnNumber} on each pending record
     * is rewritten when the runner drains and appends them (type + payload are preserved). The
     * runner drains on the same thread that executed the tool, so the ThreadLocal is visible.
     */
    private static final ThreadLocal<List<TurnRecord>> PENDING_TURNS =
            ThreadLocal.withInitial(ArrayList::new);

    /**
     * A transform-to-Leader directive requested by {@code create_group} during its execution. The
     * runner owns the turn counter + history, so it computes the compaction summary and appends the
     * REWIND/AGENT_INIT/COMPACTION_SUMMARY/USER_PROMPT sequence itself; this directive carries only
     * what the tool resolves (the brief, the registered group id, the Leader binding + Leader tool
     * set). At most one per tool call - a transform supersedes any recall.
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

    private static final ThreadLocal<TransformRequest> PENDING_TRANSFORM = new ThreadLocal<>();

    private ToolCallContextHolder() {}

    /** Sets the tool call context for the current thread. */
    public static void set(@NonNull String agentId, @NonNull UUID userId) {
        CONTEXT.set(new ToolCallContext(agentId, userId));
    }

    /** Sets the tool call context for the current thread, including the caller's group. */
    public static void set(@NonNull String agentId, @NonNull UUID userId, @Nullable UUID groupId) {
        CONTEXT.set(new ToolCallContext(agentId, userId, groupId));
    }

    /**
     * Sets the tool call context for the current thread, including the caller's group and the
     * session owner (username) whose model-tier profile resolves the caller's tier.
     */
    public static void set(
            @NonNull String agentId,
            @NonNull UUID userId,
            @Nullable UUID groupId,
            @Nullable String owner) {
        CONTEXT.set(new ToolCallContext(agentId, userId, groupId, owner));
    }

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
    public static @Nullable ToolCallContext get() {
        return CONTEXT.get();
    }

    /**
     * Requests a RECALL directive be appended to history after the current tool call returns. The
     * {@code fromIndex} suffix-drops the compiled view (keeping the seed turns, e.g. {@code 1} to
     * keep AGENT_INIT), and {@code content} is re-injected as a user message - seeding the
     * delegating agent with the recalled brief. Idempotent per tool call (a tool requests at most
     * one recall).
     */
    public static void requestRecall(int fromIndex, @NonNull String content) {
        PENDING_TURNS.get().add(TurnRecord.recall(0, fromIndex, content));
    }

    /**
     * Requests a forward transform (STANDALONE -> Leader) be applied after the current tool call
     * returns. The runner drains and applies it in the same tool-call drain pass: it appends the
     * transform turn sequence (REWIND + AGENT_INIT + COMPACTION_SUMMARY + USER_PROMPT) and mutates
     * the persona / binding / group. Supersedes any pending recall - a transform is the stronger
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
    public static @Nullable TransformRequest drainTransform() {
        TransformRequest r = PENDING_TRANSFORM.get();
        if (r != null) {
            PENDING_TRANSFORM.remove();
        }
        return r;
    }

    /**
     * Drains and clears the pending turn directives for the current thread. Called by {@link
     * AgentRunner} after a tool call returns; each entry is appended with a runner-assigned turn
     * number.
     *
     * @return the pending directives (empty if none); never null
     */
    public static @NonNull List<TurnRecord> drainPendingTurns() {
        List<TurnRecord> turns = PENDING_TURNS.get();
        if (turns.isEmpty()) {
            return List.of();
        }
        List<TurnRecord> copy = new ArrayList<>(turns);
        turns.clear();
        return copy;
    }

    /** Clears the tool call context (and any pending turn directives) for the current thread. */
    public static void clear() {
        CONTEXT.remove();
        PENDING_TURNS.remove();
        PENDING_TRANSFORM.remove();
    }
}
