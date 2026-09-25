package top.focess.veto.builtin.group;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.plugin.agent.AgentProfile;
import top.focess.veto.api.plugin.contract.AgentConfiguration;
import top.focess.veto.api.plugin.contract.JsonValue;

/** Feature-owned role policy. Responsibility text never selects host authority. */
public final class GroupProfiles {
    private GroupProfiles() {}

    private static final @NonNull Set<String> GROUP_TOOLS =
            Set.of(
                    "create_mate",
                    "create_task",
                    "create_node",
                    "remove_node",
                    "remove_mate",
                    "cancel_group_task",
                    "disband_group",
                    "inspect_group",
                    "post_message");

    static boolean owns(AgentConfiguration.@NonNull Tool tool, @NonNull Set<String> localIds) {
        String local = tool.localId();
        return "top.focess.builtin".equals(tool.pluginId())
                && local != null
                && localIds.contains(local);
    }

    /** Profile for a non-group agent: the base profile minus agent- and group-control tools. */
    public static @NonNull AgentProfile standalone(AgentConfiguration.@NonNull Context context) {
        var base = context.base();
        return new AgentProfile(
                base.name(),
                base.description(),
                base.label(),
                context.authorizedTools().stream()
                        .filter(
                                tool ->
                                        tool.capability() != ToolCapability.AGENT_CONTROL
                                                && tool.capability()
                                                        != ToolCapability.GROUP_CONTROL)
                        .filter(tool -> !owns(tool, GROUP_TOOLS))
                        .map(AgentConfiguration.Tool::name)
                        .filter(base.tools()::contains)
                        .collect(Collectors.toSet()),
                base.tier(),
                base.prompt(),
                base.metadata());
    }

    /** Builds a Leader or Mate role profile with default group configuration. */
    public static @NonNull AgentProfile role(
            AgentConfiguration.@NonNull Context context,
            @NonNull String name,
            @NonNull String description,
            boolean leader) {
        return role(context, name, description, leader, GroupConfig.defaults(), null);
    }

    /** Builds a role profile; capability filtering differs for Leaders and Mates. */
    public static @NonNull AgentProfile role(
            AgentConfiguration.@NonNull Context context,
            @NonNull String name,
            @NonNull String description,
            boolean leader,
            @NonNull GroupConfig config,
            String legacySkillset) {
        var allowed =
                leader
                        ? Set.of(
                                ToolCapability.WORKSPACE_READ,
                                ToolCapability.LOOP_CONTROL,
                                ToolCapability.USER_INTERACTION,
                                ToolCapability.PLUGIN_LOCAL)
                        : Set.of(
                                ToolCapability.WORKSPACE_READ,
                                ToolCapability.WORKSPACE_WRITE,
                                ToolCapability.PROCESS_EXECUTION,
                                ToolCapability.TASK_CONTROL,
                                ToolCapability.NETWORK_EGRESS,
                                ToolCapability.PRIVILEGED,
                                ToolCapability.PLUGIN_LOCAL,
                                ToolCapability.LOOP_CONTROL);
        var names =
                context.authorizedTools().stream()
                        .filter(tool -> allowed.contains(tool.capability()))
                        .filter(tool -> !owns(tool, Set.of("create_group")))
                        .filter(
                                tool ->
                                        !owns(
                                                tool,
                                                leader
                                                        ? Set.of(
                                                                "recall_memory",
                                                                "write_memory",
                                                                "forget_memory")
                                                        : Set.of("write_memory", "forget_memory")))
                        .filter(tool -> leader || !owns(tool, GROUP_TOOLS))
                        .map(AgentConfiguration.Tool::name)
                        .collect(Collectors.toSet());
        return new AgentProfile(
                name,
                description,
                leader ? "LEADER" : "MATE",
                names,
                config.tier(leader, legacySkillset),
                new AgentProfile.Prompt(
                        leader ? "builtin-leader-profile" : "builtin-mate-profile",
                        new JsonValue.ObjectValue(
                                Map.of(
                                        "tasks",
                                        new JsonValue.ArrayValue(List.of()),
                                        "guidance",
                                        new JsonValue.StringValue(
                                                config.guidance(leader, legacySkillset))))),
                Map.of());
    }
}
