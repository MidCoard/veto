package top.focess.veto.agent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.tool.Provenance;
import top.focess.veto.agent.tool.ToolDefinition;

/** Safe provenance for tools available to an agent or included in its latest model request. */
public record PluginContextSnapshot(
        boolean lastRequest, @NonNull List<@NonNull Participant> plugins) {
    public record Participant(
            @NonNull String id, @NonNull String version, @NonNull List<@NonNull String> tools) {}

    public static @NonNull PluginContextSnapshot from(
            @NonNull Collection<? extends @NonNull ToolDefinition> tools, boolean lastRequest) {
        var grouped = new LinkedHashMap<@NonNull String, @NonNull Group>();
        for (var tool : tools) {
            Provenance provenance = tool.provenance();
            if (provenance == null) continue;
            grouped.computeIfAbsent(
                            provenance.pluginId(),
                            ignored -> new Group(provenance.pluginVersion(), new ArrayList<>()))
                    .tools()
                    .add(tool);
        }
        var plugins =
                grouped.entrySet().stream()
                        .map(
                                entry ->
                                        new Participant(
                                                entry.getKey(),
                                                entry.getValue().version(),
                                                entry.getValue().tools().stream()
                                                        .map(ToolDefinition::name)
                                                        .distinct()
                                                        .sorted()
                                                        .toList()))
                        .sorted(Comparator.comparing(Participant::id))
                        .toList();
        return new PluginContextSnapshot(lastRequest, plugins);
    }

    private record Group(@NonNull String version, @NonNull List<@NonNull ToolDefinition> tools) {}
}
