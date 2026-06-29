package top.focess.veto.agent.mcp;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * The call context for a tool execution: the calling agent's id and the user id. Threaded from
 * {@link top.focess.veto.agent.AgentRunner} through {@link McpEngineImpl} to {@link NativeMcpTool}
 * implementations so tools like {@link top.focess.veto.group.GroupTools} can record the caller's
 * identity.
 *
 * @param agentId the id of the agent making the call (e.g., the Leader's persona id); always
 *     non-null
 * @param userId the user id of the agent's session (for multi-user tenant isolation); always
 *     non-null
 */
public record ToolCallContext(@NotNull String agentId, @NotNull UUID userId) {}
