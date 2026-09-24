package top.focess.veto.agent.identity;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.AgentProfiles;
import top.focess.veto.agent.tool.ToolDefinition;
import top.focess.veto.agent.tool.ToolEngine;
import top.focess.veto.api.plugin.agent.AgentHost;
import top.focess.veto.api.plugin.agent.AgentProfile;
import top.focess.veto.api.plugin.contract.AgentConfiguration;
import top.focess.veto.api.plugin.storage.PluginStorage;
import top.focess.veto.builtin.group.GroupProfiles;

/** Test assembly uses the shipped plugin policy, without restoring role policy to core. */
public final class BuiltinProfiles {
    private BuiltinProfiles() {}

    public static @NonNull AgentProfile profile(@NonNull Role role, @NonNull ToolEngine tools) {
        var catalog = tools.getActiveTools(null);
        var base =
                new AgentProfile(
                        "sample",
                        "sample responsibility",
                        "STANDALONE",
                        catalog.stream().map(ToolDefinition::name).collect(Collectors.toSet()),
                        null,
                        null,
                        Map.of());
        var context =
                new AgentConfiguration.Context(
                        "owner",
                        new PluginStorage.SessionScope("test", "user", "session"),
                        new AgentHost.Session() {
                            public @NonNull String id() {
                                return "session";
                            }

                            public AgentHost.@NonNull Child open(
                                    @NonNull String id,
                                    @NonNull String parent,
                                    @NonNull AgentProfile profile) {
                                throw new UnsupportedOperationException();
                            }
                        },
                        "agent",
                        base,
                        catalog.stream()
                                .map(tool -> AgentProfiles.configurationTool(tool))
                                .toList(),
                        "");
        return role.equals(Role.STANDALONE)
                ? GroupProfiles.standalone(context)
                : GroupProfiles.role(
                        context, "sample", "sample responsibility", role.equals(Role.LEADER));
    }

    public static @NonNull Set<ToolDefinition> tools(
            @NonNull Role role, @NonNull ToolEngine tools) {
        var names = profile(role, tools).tools();
        return tools.getActiveTools(null).stream()
                .filter(tool -> names.contains(tool.name()))
                .collect(Collectors.toSet());
    }
}
