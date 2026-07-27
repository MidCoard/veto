package top.focess.veto.agent.mcp.tools;

import top.focess.veto.agent.mcp.AgentToolDefinition;
import top.focess.veto.agent.mcp.Doc;

/**
 * Parameter container for the {@code create_group} agent tool (spawn a delegation). {@code
 * create_group} is an {@link AgentToolDefinition} (engine-provided, always-on). <b>Not
 * implemented</b>: the tool is registered (advertised + audited through the {@link
 * top.focess.veto.agent.mcp.ToolEngine} path) but spawning is not enabled — execution returns a
 * not-implemented observation.
 */
public record CreateGroupArgs(
        @Doc("A description/goal for the delegation to accomplish.") String description) {}
