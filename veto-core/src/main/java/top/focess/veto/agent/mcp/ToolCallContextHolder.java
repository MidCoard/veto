package top.focess.veto.agent.mcp;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.veto.agent.AgentRunner;

/**
 * Thread-local holder for {@link ToolCallContext}. {@link AgentRunner} sets the context before
 * calling {@link McpEngine#execute}, and {@link NativeMcpTool} implementations read it during
 * execution. This avoids changing the {@link NativeMcpTool} interface.
 *
 * <p><b>Usage in AgentRunner:</b>
 *
 * <pre>{@code
 * ToolCallContextHolder.set(agentId, userId);
 * try {
 *     McpToolResult result = mcpEngine.execute(call, def);
 * } finally {
 *     ToolCallContextHolder.clear();
 * }
 * }</pre>
 *
 * <p><b>Usage in NativeMcpTool:</b>
 *
 * <pre>{@code
 * ToolCallContext ctx = ToolCallContextHolder.get();
 * String callerId = ctx != null ? ctx.agentId() : "unknown";
 * }</pre>
 */
public final class ToolCallContextHolder {

    private static final ThreadLocal<ToolCallContext> CONTEXT = new ThreadLocal<>();

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

    /** Clears the tool call context for the current thread. */
    public static void clear() {
        CONTEXT.remove();
    }
}
