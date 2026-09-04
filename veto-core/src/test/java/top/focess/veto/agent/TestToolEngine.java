package top.focess.veto.agent;

import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.tool.ToolDefinition;
import top.focess.veto.agent.tool.ToolEngine;
import top.focess.veto.agent.tool.ToolResult;
import top.focess.veto.llm.core.ToolCall;

/** Empty tool registry for agent-loop unit tests that do not exercise tool dispatch. */
final class TestToolEngine implements ToolEngine {

    @Override
    public @NonNull List<ToolDefinition> getActiveTools(Set<String> whitelist) {
        return List.of();
    }

    @Override
    public ToolDefinition resolveDefinition(@NonNull String toolName) {
        return null;
    }

    @Override
    public @NonNull ToolResult execute(@NonNull ToolCall call, @NonNull ToolDefinition definition) {
        throw new AssertionError("This test does not expect tool execution");
    }
}
