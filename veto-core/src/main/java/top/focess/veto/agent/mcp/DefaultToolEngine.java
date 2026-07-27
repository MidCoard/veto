package top.focess.veto.agent.mcp;

import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.veto.llm.core.ToolCall;

/**
 * A no-op {@link ToolEngine} scaffold — registered as a {@code @ConditionalOnMissingBean} so a
 * richer implementation (server registration, schema discovery, native/agent/external dispatch)
 * overrides it when present. With no registered tools, {@link #getActiveTools} returns empty,
 * {@link #resolveDefinition} returns {@code null} (the loop surfaces a "tool not found"
 * observation), and {@link #execute} returns a failure result. This keeps the live terminal path
 * (which has no tools today) running end-to-end without depending on a richer implementation.
 *
 * <p><b>Temporary standalone-test stub.</b> Exists only so this worktree compiles + tests in
 * isolation without a richer implementation. A richer {@code ToolEngine} impl wins when present and
 * this class is removed.
 */
public class DefaultToolEngine implements ToolEngine {

    @Override
    public @NonNull List<ToolDefinition> getActiveTools(@NonNull Set<String> whitelist) {
        return List.of();
    }

    @Override
    public @Nullable ToolDefinition resolveDefinition(@NonNull String toolName) {
        return null;
    }

    @Override
    public @NonNull ToolResult execute(@NonNull ToolCall call, @NonNull ToolDefinition def) {
        return new ToolResult(call.toolName(), call.callId(), false, "no ToolEngine registered");
    }
}
