package top.focess.veto.agent.mcp;

import java.util.List;
import java.util.Set;
import top.focess.veto.llm.core.ToolCall;

/**
 * A no-op {@link McpEngine} scaffold — registered as a {@code @ConditionalOnMissingBean} so Part
 * 5's real implementation (server registration, schema discovery, native/agent/external dispatch)
 * overrides it when present. With no registered tools, {@link #getActiveTools} returns empty,
 * {@link #resolveDefinition} returns {@code null} (the loop surfaces a "tool not found"
 * observation), and {@link #execute} returns a failure result. This keeps the live terminal path
 * (which has no tools today) running end-to-end without depending on Part 5.
 */
public class DefaultMcpEngine implements McpEngine {

    @Override
    public List<ToolDefinition> getActiveTools(Set<String> whitelist) {
        return List.of();
    }

    @Override
    public ToolDefinition resolveDefinition(String toolName) {
        return null;
    }

    @Override
    public McpToolResult execute(ToolCall call, ToolDefinition def) {
        return new McpToolResult(
                call.toolName(),
                call.callId(),
                false,
                "no McpEngine registered (Part 5 not present)");
    }
}
