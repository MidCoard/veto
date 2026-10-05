package top.focess.veto.integration.plugins;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contribution.ContributionCatalog;
import top.focess.veto.api.plugin.contribution.ContributionId;
import top.focess.veto.api.plugin.contribution.PluginContributionsDirectory;
import top.focess.veto.plugin.runtime.ManagedPlugin;

/**
 * Prepared contribution-protocol discovery for one publication. Receiver and provider activations
 * are captured once; each concurrent query rechecks their live state and the caller's visibility.
 * Queries do not invoke plugin bodies or retain registration builders.
 */
final class PluginContributionDirectory {
    private record Entry(
            PluginContributionsDirectory.@NonNull Entry value, @NonNull ManagedPlugin owner) {}

    private record Group(
            int major, @NonNull ManagedPlugin owner, @NonNull List<@NonNull Entry> entries) {}

    private final @NonNull Map<ContributionId, Group> groups;

    PluginContributionDirectory(
            @NonNull ContributionCatalog catalog,
            @NonNull Map<@NonNull String, @NonNull ManagedPlugin> owners) {
        Map<ContributionId, Group> prepared = new HashMap<>();
        for (var definition : catalog.entries(StandardContributionPoints.CONTRIBUTIONS)) {
            var owner = owners.get(definition.source().namespace());
            if (owner == null) throw new IllegalArgumentException("Contribution owner unavailable");
            List<@NonNull Entry> entries = new ArrayList<>();
            for (var contribution : catalog.entries(definition.implementation().point())) {
                var provider = owners.get(contribution.source().namespace());
                if (provider == null)
                    throw new IllegalArgumentException("Contribution provider unavailable");
                entries.add(
                        new Entry(
                                new PluginContributionsDirectory.Entry(
                                        contribution.id(),
                                        contribution.source().namespace(),
                                        contribution.implementation()),
                                provider));
            }
            prepared.put(
                    definition.implementation().id(),
                    new Group(definition.implementation().major(), owner, List.copyOf(entries)));
        }
        groups = Map.copyOf(prepared);
    }

    // WHY: provider owners belong to PluginManager; this directory only queries admission state.
    @SuppressWarnings("resource")
    @NonNull List<PluginContributionsDirectory.Entry> entries(
            @NonNull ContributionId pointId,
            int major,
            @NonNull ManagedPlugin caller,
            @NonNull BiPredicate<@NonNull String, @NonNull String> visible) {
        var group = groups.get(pointId);
        if (group == null || group.major() != major || caller.state() != PluginState.ACTIVE)
            return List.of();
        if (group.owner().state() != PluginState.ACTIVE
                || !visible.test(caller.identity().id(), group.owner().identity().id()))
            return List.of();
        var result = new ArrayList<PluginContributionsDirectory.Entry>();
        for (var entry : group.entries())
            if (entry.owner().state() == PluginState.ACTIVE
                    && visible.test(caller.identity().id(), entry.value().providerId()))
                result.add(entry.value());
        return List.copyOf(result);
    }
}
