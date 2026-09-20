package top.focess.veto.agent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.tool.PluginToolDefinition;
import top.focess.veto.agent.tool.ToolDefinition;

/** Safe provenance for tools available to an agent or included in its latest model request. */
public record PluginContextSnapshot(
        boolean lastRequest, @NonNull List<@NonNull Participant> plugins) {
    public record Participant(
            @NonNull String id, @NonNull String version, @NonNull List<@NonNull String> tools) {}

    public static @NonNull PluginContextSnapshot from(
            @NonNull Collection<? extends ToolDefinition> tools, boolean lastRequest) {
        var grouped = new TreeMap<@NonNull String, @NonNull List<@NonNull PluginToolDefinition>>();
        for (var tool : tools) {
            if (tool instanceof PluginToolDefinition plugin) {
                grouped.computeIfAbsent(plugin.pluginId(), ignored -> new ArrayList<>())
                        .add(plugin);
            }
        }
        var plugins =
                grouped.entrySet().stream()
                        .map(
                                entry ->
                                        new Participant(
                                                entry.getKey(),
                                                entry.getValue().getFirst().pluginVersion(),
                                                entry.getValue().stream()
                                                        .map(PluginToolDefinition::name)
                                                        .distinct()
                                                        .sorted()
                                                        .toList()))
                        .sorted(Comparator.comparing(Participant::id))
                        .toList();
        return new PluginContextSnapshot(lastRequest, plugins);
    }
}
