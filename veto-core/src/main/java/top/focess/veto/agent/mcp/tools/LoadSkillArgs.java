package top.focess.veto.agent.mcp.tools;

import top.focess.veto.agent.mcp.Doc;

/**
 * Parameter container for the {@code load_skill} agent tool. Transcribed from {@code
 * mcp_tool_foundation.md} §10.7. {@code load_skill} is an {@link
 * top.focess.veto.agent.mcp.AgentToolDefinition} (engine-provided, always-on), not a {@link
 * top.focess.veto.agent.mcp.NativeMcpTool}; its args record carries the schema source reflected by
 * the translator.
 */
public record LoadSkillArgs(
        @Doc("The name of the skill to load (e.g. 'verify_suite').") String skillName) {}
