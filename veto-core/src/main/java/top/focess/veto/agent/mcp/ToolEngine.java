package top.focess.veto.agent.mcp;

import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.veto.llm.core.ToolCall;

/**
 * The tool engine — manages server registrations, schema discovery, and tool dispatching. The loop
 * calls the methods below.
 *
 * <p><b>Note:</b> the interface also lists {@code registerServer(String, McpTransport)} and {@code
 * executeTool(String, Map)} — these are implementation details (server setup; low-level transport
 * dispatch) that the loop never calls, so they are intentionally absent from this shared interface.
 * A richer {@code ToolEngine} implementation adds {@code registerServer} + the {@code McpTransport}
 * types. {@code executeTool(String, Map)} is realized internally by {@link #execute(ToolCall,
 * ToolDefinition)} which dispatches by the resolved definition's flavour (native in-process / agent
 * handler / external transport). {@code resolveDefinition} is required by the loop though omitted
 * from the listing — it is included here. Do not modify this shared interface without coordinator
 * approval; if insufficient, stop and report.
 */
public interface ToolEngine {

    /** Queries all registered servers to compile a whitelisted tools list for the agent. */
    @NonNull List<ToolDefinition> getActiveTools(@Nullable Set<String> whitelist);

    /** Resolves a tool name to its typed {@link ToolDefinition} (native / remote / agent). */
    @Nullable ToolDefinition resolveDefinition(@NonNull String toolName);

    /** Executes a tool call, dispatching by the resolved definition's flavour. */
    @NonNull ToolResult execute(@NonNull ToolCall call, @NonNull ToolDefinition def);
}
