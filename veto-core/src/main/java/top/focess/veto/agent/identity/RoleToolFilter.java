package top.focess.veto.agent.identity;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.mcp.ToolDefinition;
import top.focess.veto.agent.mcp.ToolEngine;

/**
 * Resolves the role-scoped tool manifest for an {@link AgentPersona}. The raw {@link
 * ToolEngine#getActiveTools} set is the union of every registered native + agent + remote tool;
 * that full set is correct only for a top-level STANDALONE agent. A Leader (decomposes + arranges
 * the DAG, never executes task nodes itself) and a Mate (executes, never delegates) each see a
 * different slice:
 *
 * <ul>
 *   <li><b>STANDALONE</b> - everything <em>except</em> the Leader-only arrangement tools ({@code
 *       create_node}, {@code remove_node}, {@code post_message}, {@code disband_group}). Keeps
 *       {@code create_group} (the delegation transform), the file/command tools, memory, and the
 *       always-on meta tools. Deny-list based so newly registered tools (e.g. a future remote MCP
 *       tool) surface to the standalone agent by default.
 *   <li><b>MATE</b> - everything <em>except</em> all group tools (no {@code create_group}, no
 *       arrangement). A Mate executes task nodes with the file/command + memory tools; it never
 *       delegates or arranges. Also deny-list based.
 *   <li><b>LEADER</b> - a strict allow-list: the read-only investigation tools ({@code view_file},
 *       {@code grep_search}, {@code list_dir}) so it can research the brief, the arrangement tools
 *       ({@code create_node}/{@code remove_node}/{@code post_message}/{@code disband_group}) so it
 *       authors the DAG and talks to the blackboard, and the always-on meta tools. No {@code
 *       create_group} (a Leader never spawns sub-groups), no file-write/command tools (a Leader
 *       never executes task nodes), no memory tools. Mates are provisioned lazily by the
 *       orchestrator on dispatch, so the Leader has no mate-spawning tool.
 * </ul>
 *
 * <p>Filtering the <em>resolved</em> {@link ToolDefinition} list (post-{@code getActiveTools}) -
 * not the name-whitelist param - is deliberate: {@link ToolEngine#getActiveTools} always includes
 * the agent tools ({@code load_skill}, {@code think}) regardless of the whitelist, so only a
 * post-resolution filter can scope them.
 */
@Component
public class RoleToolFilter {

    /** Leader-only arrangement tools - never exposed to STANDALONE or MATE. */
    private static final @NonNull Set<@NonNull String> ARRANGE =
            Set.of("create_node", "remove_node", "post_message", "disband_group");

    /** All group tools - never exposed to a MATE (it neither delegates nor arranges). */
    private static final @NonNull Set<@NonNull String> GROUP =
            Set.of("create_group", "create_node", "remove_node", "post_message", "disband_group");

    /**
     * The Leader's strict allow-list: read-only investigation + arrangement + the always-on meta
     * tools. No file-write, no command exec, no memory, no create_group.
     */
    private static final @NonNull Set<@NonNull String> LEADER_ALLOWED =
            Set.of(
                    "view_file",
                    "grep_search",
                    "list_dir",
                    "create_node",
                    "remove_node",
                    "post_message",
                    "disband_group",
                    "load_skill",
                    "think");

    private final @NonNull ToolEngine mcpEngine;

    public RoleToolFilter(@NonNull ToolEngine mcpEngine) {
        this.mcpEngine = mcpEngine;
    }

    /** Resolves the role-scoped, unmodifiable tool set for the given role. */
    public @NonNull Set<ToolDefinition> resolve(@NonNull Role role) {
        List<ToolDefinition> all = mcpEngine.getActiveTools(null);
        return switch (role) {
            case STANDALONE ->
                    all.stream()
                            .filter(t -> !ARRANGE.contains(t.name()))
                            .collect(Collectors.toUnmodifiableSet());
            case MATE ->
                    all.stream()
                            .filter(t -> !GROUP.contains(t.name()))
                            .collect(Collectors.toUnmodifiableSet());
            case LEADER ->
                    all.stream()
                            .filter(t -> LEADER_ALLOWED.contains(t.name()))
                            .collect(Collectors.toUnmodifiableSet());
        };
    }
}
