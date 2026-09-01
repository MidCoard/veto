package top.focess.veto.agent.mcp;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.AgentRunner;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.group.GroupTools;
import top.focess.veto.llm.core.ToolResultPresentationMode;

/**
 * The call context for a tool execution: the calling agent's id, the user id, the group id when the
 * caller belongs to a group, and the session owner (username). Threaded from {@link AgentRunner}
 * through {@code ToolEngineImpl} to {@link NativeTool} and {@link AgentTool} implementations so
 * tools like {@link GroupTools} can record the caller's identity, and so group-spawned Mates /
 * Leaders resolve their model tier against the <em>session owner's</em> active profile (per-user
 * model-tier configuration).
 *
 * @param agentId the id of the agent making the call (e.g., the Leader's persona id); always
 *     non-null
 * @param userId the user id of the agent's session (for multi-user tenant isolation); always
 *     non-null
 * @param groupId the id of the group the calling agent belongs to (the group it leads, or the group
 *     it is a Mate of); null when the caller is a single-agent (STANDALONE) loop
 * @param owner the session owner (username) whose model-tier profile resolves the caller's tier;
 *     null in legacy/test paths that bypass session activation
 * @param sessionId the session this call's agent belongs to; used to route session-scoped events
 *     (e.g. background-task lifecycle) on the delta broker. Null in legacy/test paths.
 */
public record ToolCallContext(
        @NonNull String agentId,
        @NonNull UUID userId,
        UUID groupId,
        String owner,
        UUID sessionId,
        @NonNull ToolResultPresentationMode toolResultPresentation,
        @NonNull ToolExecutionPermit executionPermit) {}
