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
 *   <li><b>MATE</b> - a fail-closed allow-list of execution, observation, and read-only recall
 *       tools. A Mate cannot mutate cross-session memory and a newly registered high-privilege tool
 *       is not exposed accidentally.
 *   <li><b>LEADER</b> - a strict allow-list: the read-only investigation tools ({@code view_file},
 *       {@code grep_search}, {@code list_dir}) so it can research the brief, the arrangement tools
 *       ({@code create_node}/{@code remove_node}/{@code inspect_group}/{@code disband_group}) so it
 *       authors and inspects the DAG, and the always-on meta tools. No {@code create_group} (a
 *       Leader never spawns sub-groups), no file-write/command tools (a Leader never executes task
 *       nodes), no memory tools. Mates are provisioned lazily by the orchestrator on dispatch, so
 *       the Leader has no mate-spawning tool.
 * </ul>
 *
 * <p>Filtering the <em>resolved</em> {@link ToolDefinition} list (post-{@code getActiveTools}) -
 * not the name-whitelist param - is deliberate: {@link ToolEngine#getActiveTools} always includes
 * the agent tools ({@code load_skill}, {@code think}) regardless of the whitelist, so only a
 * post-resolution filter can scope them. The prompt compiler performs the final conditional
 * capability pass and removes {@code load_skill} when the resolved persona has no skills.
 */
@Component
public class RoleToolFilter {

    /** Leader-only arrangement tools - never exposed to STANDALONE or MATE. */
    private static final @NonNull Set<@NonNull String> ARRANGE =
            Set.of("create_node", "remove_node", "post_message", "inspect_group", "disband_group");

    /** A Mate's complete capability set. New tools remain unavailable until reviewed here. */
    private static final @NonNull Set<@NonNull String> MATE_ALLOWED =
            Set.of(
                    "grep_search",
                    "list_dir",
                    "load_skill",
                    "recall_insights",
                    "recall_session",
                    "replace_file_content",
                    "run_command",
                    "run_task",
                    "stop_task",
                    "think",
                    "view_file",
                    "view_task",
                    "web_fetch",
                    "web_search",
                    "write_to_file");

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
                    "inspect_group",
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
                            .filter(t -> MATE_ALLOWED.contains(t.name()))
                            .collect(Collectors.toUnmodifiableSet());
            case LEADER ->
                    all.stream()
                            .filter(t -> LEADER_ALLOWED.contains(t.name()))
                            .collect(Collectors.toUnmodifiableSet());
        };
    }
}
