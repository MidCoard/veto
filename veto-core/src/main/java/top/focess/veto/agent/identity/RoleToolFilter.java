package top.focess.veto.agent.identity;

import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.mcp.ToolCapability;
import top.focess.veto.agent.mcp.ToolDefinition;
import top.focess.veto.agent.mcp.ToolEngine;

/**
 * Resolves the role-scoped tool manifest for an {@link AgentPersona}. The raw {@link
 * ToolEngine#getActiveTools} set is the union of every registered native + agent + remote tool;
 * that full set is correct only for a top-level STANDALONE agent. A Leader (decomposes + arranges
 * the DAG, never executes task nodes itself) and a Mate (executes, never delegates) each see a
 * different capability slice:
 *
 * <ul>
 *   <li><b>STANDALONE</b> - workspace execution, network, skills, memory, loop control, delegation,
 *       and configured remote tools. It cannot mutate an active group's DAG or lifecycle.
 *   <li><b>MATE</b> - execution, observation, skill loading, read-only recall, and loop control. A
 *       Mate cannot mutate cross-session memory or delegate.
 *   <li><b>LEADER</b> - read-only workspace investigation, skill loading, loop control, and group
 *       control. It can author/inspect the DAG and communicate with Mates, but cannot execute task
 *       nodes or spawn nested groups.
 * </ul>
 *
 * <p>Filtering the <em>resolved</em> {@link ToolDefinition} list (post-{@code getActiveTools}) -
 * not the name-whitelist param - is deliberate: {@link ToolEngine#getActiveTools} always includes
 * the agent tools ({@code load_skill}, {@code think}) regardless of the whitelist, so only a
 * post-resolution filter can scope them. Names identify calls; capabilities authorize sets of
 * effects. The prompt compiler performs the final conditional pass and removes {@code load_skill}
 * when the resolved persona has no skills.
 */
@Component
public class RoleToolFilter {

    private static final @NonNull Set<@NonNull ToolCapability> STANDALONE_CAPABILITIES =
            Set.of(
                    ToolCapability.WORKSPACE_READ,
                    ToolCapability.WORKSPACE_WRITE,
                    ToolCapability.PROCESS_EXECUTION,
                    ToolCapability.TASK_CONTROL,
                    ToolCapability.NETWORK_EGRESS,
                    ToolCapability.SKILL_READ,
                    ToolCapability.MEMORY_READ,
                    ToolCapability.MEMORY_WRITE,
                    ToolCapability.LOOP_CONTROL,
                    ToolCapability.DELEGATION,
                    ToolCapability.REMOTE_UNKNOWN);

    private static final @NonNull Set<@NonNull ToolCapability> MATE_CAPABILITIES =
            Set.of(
                    ToolCapability.WORKSPACE_READ,
                    ToolCapability.WORKSPACE_WRITE,
                    ToolCapability.PROCESS_EXECUTION,
                    ToolCapability.TASK_CONTROL,
                    ToolCapability.NETWORK_EGRESS,
                    ToolCapability.SKILL_READ,
                    ToolCapability.MEMORY_READ,
                    ToolCapability.LOOP_CONTROL);

    private static final @NonNull Set<@NonNull ToolCapability> LEADER_CAPABILITIES =
            Set.of(
                    ToolCapability.WORKSPACE_READ,
                    ToolCapability.SKILL_READ,
                    ToolCapability.LOOP_CONTROL,
                    ToolCapability.GROUP_CONTROL);

    private final @NonNull ToolEngine mcpEngine;

    public RoleToolFilter(@NonNull ToolEngine mcpEngine) {
        this.mcpEngine = mcpEngine;
    }

    /** Resolves the role-scoped, unmodifiable tool set for the given role. */
    public @NonNull Set<@NonNull ToolDefinition> resolve(@NonNull Role role) {
        return resolve(role, capabilitiesFor(role));
    }

    /**
     * Resolves a narrower manifest inside the role ceiling. This is the extension point for a Mate
     * specialty or another session-scoped capability selection: selecting a capability can never
     * grant one forbidden to the role.
     */
    public @NonNull Set<@NonNull ToolDefinition> resolve(
            @NonNull Role role, @NonNull Set<@NonNull ToolCapability> selectedCapabilities) {
        Set<@NonNull ToolCapability> allowed = capabilitiesFor(role);
        return mcpEngine.getActiveTools(null).stream()
                .filter(tool -> allowed.contains(tool.capability()))
                .filter(tool -> selectedCapabilities.contains(tool.capability()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** Returns the immutable capability ceiling for a role. */
    public static @NonNull Set<@NonNull ToolCapability> capabilitiesFor(@NonNull Role role) {
        return switch (role) {
            case STANDALONE -> STANDALONE_CAPABILITIES;
            case MATE -> MATE_CAPABILITIES;
            case LEADER -> LEADER_CAPABILITIES;
        };
    }
}
