package top.focess.veto.integration.plugins;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.Tool;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contribution.ContributionCatalog;
import top.focess.veto.api.plugin.contribution.ContributionEntry;
import top.focess.veto.api.plugin.contribution.ContributionId;
import top.focess.veto.api.plugin.contribution.ContributionPoint;
import top.focess.veto.api.plugin.contribution.PluginContributionsDirectory;
import top.focess.veto.event.EventListenerRegistry;
import top.focess.veto.plugin.runtime.CompositeAgentInbox;
import top.focess.veto.plugin.runtime.InstalledPluginLoader;
import top.focess.veto.plugin.runtime.ManagedPlugin;

/**
 * Immutable queries over one published plugin generation. Capturing this registry keeps
 * contribution implementations, names, metadata and exact activation owners together. Lifecycle
 * state and admission remain live; a retained registry does not keep a stopped activation callable.
 * Concurrent reads are supported and never consult mutable registration staging.
 */
public final class PluginRegistry {
    private final @NonNull List<ManagedPlugin> plugins;
    private final @NonNull Map<@NonNull String, @NonNull ManagedPlugin> owners;
    private final @NonNull List<InstalledPluginLoader.DisabledPackage> disabled;
    private final @NonNull List<PluginManager.DeclinedPlugin> declined;
    private final @NonNull ContributionCatalog catalog;
    private final @NonNull EventListenerRegistry events;
    private final @NonNull List<CompositeAgentInbox.@NonNull Entry> inboxes;
    private final @NonNull Map<String, String> aliases;
    private final @NonNull Map<String, String> toolNames;
    private final @NonNull Set<String> installedIds;
    private final @NonNull PluginContributionDirectory directory;

    PluginRegistry(
            @NonNull List<ManagedPlugin> plugins,
            @NonNull List<InstalledPluginLoader.DisabledPackage> disabled,
            @NonNull List<PluginManager.DeclinedPlugin> declined,
            @NonNull ContributionCatalog catalog,
            @NonNull EventListenerRegistry events,
            @NonNull Map<String, String> aliases,
            @NonNull Map<String, String> toolNames,
            @NonNull Set<String> installedIds) {
        this.plugins = List.copyOf(plugins);
        Map<@NonNull String, @NonNull ManagedPlugin> indexed = new HashMap<>();
        for (var plugin : plugins)
            if (indexed.putIfAbsent(plugin.identity().id(), plugin) != null)
                throw new IllegalArgumentException("Duplicate plugin activation");
        owners = Map.copyOf(indexed);
        this.disabled = List.copyOf(disabled);
        this.declined = List.copyOf(declined);
        this.catalog = catalog;
        this.events = events;
        this.aliases = Map.copyOf(aliases);
        this.toolNames = Map.copyOf(toolNames);
        this.installedIds = Set.copyOf(installedIds);
        List<CompositeAgentInbox.@NonNull Entry> prepared = new ArrayList<>();
        for (var entry : catalog.entries(StandardContributionPoints.AGENT_INBOX)) {
            var owner = owners.get(entry.source().namespace());
            if (owner == null) throw new IllegalArgumentException("Inbox activation is missing");
            prepared.add(
                    new CompositeAgentInbox.Entry(
                            entry.id().value(), owner, entry.implementation()));
        }
        inboxes = List.copyOf(prepared);
        directory = new PluginContributionDirectory(catalog, owners);
    }

    static @NonNull PluginRegistry empty() {
        var builder = new ContributionCatalog.Builder();
        for (var point : StandardContributionPoints.ALL) builder.define(point, ignored -> {});
        var catalog = builder.freeze();
        return new PluginRegistry(
                List.of(),
                List.of(),
                List.of(),
                catalog,
                EventListenerRegistry.build(
                        catalog, Map.of(), new EventListenerRegistry.Preparation()),
                Map.of(),
                Map.of(),
                Set.of());
    }

    /** Returns the prepared entries of an exact contribution point. */
    public <T> @NonNull List<ContributionEntry<T>> entries(@NonNull ContributionPoint<T> point) {
        return catalog.entries(point);
    }

    /** Returns immutable sorted point identities contributed by this plugin. */
    public @NonNull List<String> pointIds(@NonNull String namespace) {
        return catalog.points(canonicalId(namespace)).stream().map(point -> point.value()).toList();
    }

    /** Resolves a canonical or historical identity against this exact generation. */
    public @NonNull ManagedPlugin plugin(@NonNull String id) {
        var owner = owners.get(canonicalId(id));
        if (owner == null) throw new IllegalArgumentException("Unknown plugin identity");
        return owner;
    }

    /** Maps historical identities; unknown identities pass through. */
    public @NonNull String canonicalId(@NonNull String id) {
        return aliases.getOrDefault(id, id);
    }

    /** Resolves a contributed tool name using its captured owner and operator preferences. */
    public @NonNull String toolName(@NonNull ContributionEntry<Tool> entry) {
        var namespace = entry.source().namespace();
        return resolveToolName(
                namespace,
                entry.id().value(),
                toolNames,
                plugin(namespace),
                installedIds.contains(namespace));
    }

    static @NonNull String resolveToolName(
            @NonNull String namespace,
            @NonNull String qualifiedId,
            @NonNull Map<String, String> names,
            @NonNull ManagedPlugin implementation,
            boolean installed) {
        String alias = names.get(qualifiedId);
        if (alias != null) return alias;
        String local = qualifiedId.substring(qualifiedId.indexOf(':') + 1);
        String preferred = installed ? implementation.preferredToolName(local) : null;
        if (preferred != null) {
            if (preferred.isBlank()) throw new IllegalArgumentException("Blank plugin tool name");
            return preferred;
        }
        return "plugin_" + namespace.replace('.', '_').replace('-', '_') + "__" + local;
    }

    /** Captured activation owners; individual lifecycle state remains live. */
    public @NonNull List<ManagedPlugin> plugins() {
        return plugins;
    }

    /** Installed packages disabled for this start. */
    public @NonNull List<InstalledPluginLoader.DisabledPackage> disabled() {
        return disabled;
    }

    /** Installed packages which declined this activation. */
    public @NonNull List<PluginManager.DeclinedPlugin> declined() {
        return declined;
    }

    /** Prepared listener routes bound to the captured owners. */
    public @NonNull EventListenerRegistry events() {
        return events;
    }

    /** Prepared inbox sources bound to the captured owners. */
    public @NonNull List<CompositeAgentInbox.@NonNull Entry> inboxes() {
        return inboxes;
    }

    @NonNull ContributionCatalog catalog() {
        return catalog;
    }

    @NonNull List<PluginContributionsDirectory.Entry> contributions(
            @NonNull ContributionId pointId,
            int major,
            @NonNull ManagedPlugin caller,
            @NonNull BiPredicate<@NonNull String, @NonNull String> visible) {
        return directory.entries(pointId, major, caller, visible);
    }
}
