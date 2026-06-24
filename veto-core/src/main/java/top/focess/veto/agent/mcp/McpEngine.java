package top.focess.veto.agent.mcp;

import java.util.List;
import java.util.Set;
import top.focess.veto.llm.core.ToolCall;

/**
 * The MCP engine — manages server registrations, schema discovery, and tool dispatching. Part 5
 * owns the implementation; Part 1's loop calls the methods below. Transcribed (loop-facing surface)
 * from {@code plans/mvp-core/part5_agent/mcp_tool_foundation.md} §2 + the authoritative usage in
 * {@code plans/mvp-core/part1_loop/hybrid_loop_design.md} §3.2.1 and {@code
 * plans/mvp-core/part1_loop/loop_interception_drift.md} §2.2.
 *
 * <p><b>Phase-0 contract note:</b> the LLD §2 interface also lists {@code registerServer(String,
 * McpTransport)} and {@code executeTool(String, Map)} — these are Part-5-owned implementation
 * details (server setup; low-level transport dispatch) that Part 1 never calls, so they are
 * intentionally absent from this shared interface. Part 5's {@code McpEngine} implementation adds
 * {@code registerServer} + the {@code McpTransport} types. {@code executeTool(String, Map)} is
 * realized internally by {@link #execute(ToolCall, ToolDefinition)} which dispatches by the
 * resolved definition's flavour (native in-process / agent handler / external transport). {@code
 * resolveDefinition} is required by the loop (hybrid_loop §3.2.1 step 4a) though omitted from the
 * §2 listing — it is included here. Do not modify this shared interface without coordinator
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
