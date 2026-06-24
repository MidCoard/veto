package top.focess.veto.agent.mcp.tools;

import top.focess.veto.agent.mcp.Doc;

/**
 * Parameter container for the {@code create_group} agent tool (spawn a delegation). Transcribed
 * from {@code mcp_tool_foundation.md} §5.1/§6.4. {@code create_group} is an {@link
 * top.focess.veto.agent.mcp.AgentToolDefinition} (engine-provided, always-on). <b>MVP stub</b>: the
 * tool is registered (advertised + audited through the {@link top.focess.veto.agent.mcp.McpEngine}
 * path) but spawning is bounded by the resource gate ([Part 2.2], Phase-2 groups are out of MVP
 * scope per Feature 15.2) — execution returns a not-implemented observation.
 */
public record CreateGroupArgs(
        @Doc("A description/goal for the delegation to accomplish.") String description) {}
