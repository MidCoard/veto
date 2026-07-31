package top.focess.veto.agent.mcp;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.veto.agent.AgentRunner;
import top.focess.veto.group.GroupTools;

/**
 * The call context for a tool execution: the calling agent's id and the user id, plus the group id
 * when the caller belongs to a group. Threaded from {@link AgentRunner} through {@link
 * ToolEngineImpl} to {@link NativeTool} and {@link AgentTool} implementations so tools like {@link
 * GroupTools} can record the caller's identity.
 *
 * @param agentId the id of the agent making the call (e.g., the Leader's persona id); always
 *     non-null
 * @param userId the user id of the agent's session (for multi-user tenant isolation); always
 *     non-null
 * @param groupId the id of the group the calling agent belongs to (the group it leads, or the group
 *     it is a Mate of); null when the caller is a single-agent (STANDALONE) loop
 */
public record ToolCallContext(
        @NonNull String agentId, @NonNull UUID userId, @Nullable UUID groupId) {

    /** Compatibility constructor for callers without a group (STANDALONE agents). */
    public ToolCallContext(@NonNull String agentId, @NonNull UUID userId) {
        this(agentId, userId, null);
    }
}
