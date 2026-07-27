package top.focess.veto.agent.mcp;

import java.util.ArrayList;
import java.util.List;
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

    private ToolCallContextHolder() {}

    /** Sets the tool call context for the current thread. */
    public static void set(@NonNull String agentId, @NonNull UUID userId) {
        CONTEXT.set(new ToolCallContext(agentId, userId));
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
    }
}
