package top.focess.veto.agent.mcp;

import java.util.List;
import java.util.Set;
import top.focess.veto.llm.core.ToolCall;

/**
 * The MCP engine — manages server registrations, schema discovery, and tool dispatching. The loop
 * calls the methods below.
 *
 * <p><b>Note:</b> the interface also lists {@code registerServer(String, McpTransport)} and {@code
 * executeTool(String, Map)} — these are implementation details (server setup; low-level transport
 * dispatch) that the loop never calls, so they are intentionally absent from this shared interface.
 * A richer {@code McpEngine} implementation adds {@code registerServer} + the {@code McpTransport}
 * types. {@code executeTool(String, Map)} is realized internally by {@link #execute(ToolCall,
 * ToolDefinition)} which dispatches by the resolved definition's flavour (native in-process / agent
 * handler / external transport). {@code resolveDefinition} is required by the loop though omitted
 * from the listing — it is included here. Do not modify this shared interface without coordinator
 * approval; if insufficient, stop and report.
 */
public interface McpEngine {

    /** Queries all registered servers to compile a whitelisted tools list for the agent. */
    List<ToolDefinition> getActiveTools(Set<String> whitelist);

    /** Resolves a tool name to its typed {@link ToolDefinition} (native / remote / agent). */
    ToolDefinition resolveDefinition(String toolName);

    /** Executes a tool call, dispatching by the resolved definition's flavour. */
    McpToolResult execute(ToolCall call, ToolDefinition def);
}
