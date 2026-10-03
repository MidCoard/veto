package top.focess.veto.integration.plugins;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import top.focess.veto.agent.tool.ToolDefinition;
import top.focess.veto.api.agent.control.SourceEvidence;
import top.focess.veto.api.llm.VetoResponse;
import top.focess.veto.api.plugin.PluginBinding;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.agent.AgentHost;
import top.focess.veto.api.plugin.agent.AgentProfile;
import top.focess.veto.api.plugin.contract.AgentConfiguration;
import top.focess.veto.api.plugin.contract.AgentInbox;
import top.focess.veto.api.plugin.contract.ModelResponsePolicy;
import top.focess.veto.api.plugin.contract.PluginFailure;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contribution.ContributionPoint;
import top.focess.veto.api.plugin.storage.PluginStorage;
import top.focess.veto.integration.plugins.storage.PluginInvocationContext;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.plugin.runtime.*;

/**
 * Resolves explicit session plugin bindings against the current publication. Installed packages are
 * a catalog, never a global availability switch. Reads do not modify session selection.
 */
@Service
public class SessionPlugins {
    private final @NonNull PluginManager manager;
    private final @NonNull SessionRepository sessions;

    /** Creates the selection service over the plugin manager and session store. */
    public SessionPlugins(@NonNull PluginManager manager, @NonNull SessionRepository sessions) {
        this.manager = manager;
        this.sessions = sessions;
    }

    /**
     * Resolves the requested ids (null selects every installed plugin) to pinned bindings;
     * duplicates and unknown or inactive plugins are rejected.
     */
    public @NonNull List<PluginBinding> selection(List<String> requested) {
        var publication = manager.registry();
        var available = publication.plugins();
        var ids =
                requested == null
                        ? available.stream().map(p -> p.identity().id()).toList()
                        : requested.stream().map(publication::canonicalId).toList();
        if (ids.size() != Set.copyOf(ids).size())
            throw new IllegalArgumentException("Duplicate plugin selection");
        return ids.stream()
                .map(
                        id -> {
                            var plugin = publication.plugin(id);
                            if (plugin.state() != PluginState.ACTIVE)
                                throw new IllegalArgumentException("Plugin is unavailable: " + id);
                            return plugin.binding();
                        })
                .toList();
    }

    /**
     * Returns explicit persisted bindings. An empty selection is valid; missing bindings are
     * invalid. Missing or changed plugins remain pinned and are reported through availability.
     */
    public @NonNull List<PluginBinding> bindings(@NonNull String sessionId) {
        var session =
                sessions.findById(sessionId)
                        .orElseThrow(() -> new IllegalStateException("Session not found"));
        var bindings = session.getPluginBindings();
        if (bindings == null)
            throw new IllegalStateException("Session plugin bindings are missing");
        return bindings;
    }

    /**
     * Collects the single agent-configuration intent of the selected contributors; null when no
     * contributor applies, and conflicting intents fail.
     */
    public AgentConfiguration.Intent configure(
            @NonNull String owner,
            @NonNull String session,
            @NonNull String agent,
            String configurationOwner,
            @NonNull AgentProfile base,
            @NonNull List<AgentConfiguration.Tool> tools,
            @NonNull String activeTask) {
        var publication = manager.registry();
        var entries = publication.entries(StandardContributionPoints.AGENT_CONFIGURATION);
        if (entries.isEmpty()) return null;
        var selected = selectedIds(session, publication);
        AgentConfiguration.Intent result = null;
        for (var entry : entries) {
            String namespace = entry.source().namespace();
            if (!selected.contains(namespace)
                    || (configurationOwner != null && !configurationOwner.equals(namespace)))
                continue;
            var plugin = publication.plugin(namespace);
            if (plugin.state() != PluginState.ACTIVE) continue;
            var storage = manager.hostService(namespace, PluginStorage.class);
            var host = manager.hostService(namespace, AgentHost.class);
            if (storage == null || host == null)
                throw new IllegalStateException("Agent configuration services unavailable");
            var invocation = new PluginInvocationContext(owner, session);
            try {
                var scope = storage.currentSession();
                var context =
                        new AgentConfiguration.Context(
                                owner, scope, host.session(scope), agent, base, tools, activeTask);
                var intent =
                        plugin.execute(
                                        () ->
                                                Optional.ofNullable(
                                                        entry.implementation().configure(context)))
                                .orElse(null);
                if (intent != null) {
                    if (result != null)
                        throw new IllegalStateException(
                                "Conflicting agent configuration contributions");
                    result = intent;
                }
            } catch (PluginFailure error) {
                throw new IllegalStateException("Agent configuration failed", error);
            } finally {
                invocation.close();
            }
        }
        return result;
    }

    /** Opens the model-response policies of the session's selected plugins in catalog order. */
    public @NonNull List<ModelResponsePolicy.Exchange> responsePolicies(@NonNull String sessionId) {
        var publication = manager.registry();
        var ids = selectedIds(sessionId, publication);
        List<ModelResponsePolicy.Exchange> result = new ArrayList<>();
        for (var entry : publication.entries(StandardContributionPoints.MODEL_RESPONSE)) {
            if (!ids.contains(entry.source().namespace())) continue;
            var plugin = publication.plugin(entry.source().namespace());
            try {
                var policy = plugin.execute(() -> entry.implementation().open());
                result.add(
                        new ModelResponsePolicy.Exchange() {
                            public ModelResponsePolicy.@NonNull Result check(
                                    @NonNull VetoResponse response,
                                    @NonNull SourceEvidence evidence) {
                                try {
                                    return plugin.execute(() -> policy.check(response, evidence));
                                } catch (PluginFailure failure) {
                                    throw new IllegalStateException(
                                            "Model response policy unavailable");
                                }
                            }

                            public ModelResponsePolicy.Result rejected(int count) {
                                try {
                                    return plugin.execute(
                                                    () ->
                                                            Optional.ofNullable(
                                                                    policy.rejected(count)))
                                            .orElse(null);
                                } catch (PluginFailure failure) {
                                    throw new IllegalStateException(
                                            "Model response policy unavailable");
                                }
                            }
                        });
            } catch (PluginFailure failure) {
                throw new IllegalStateException("Model response policy unavailable");
            }
        }
        return List.copyOf(result);
    }

    /** Returns a lazily resolved composite work source of the session's selected plugins. */
    public @NonNull AgentInbox workSource(@NonNull String sessionId) {
        return new CompositeAgentInbox(
                () -> {
                    var publication = manager.registry();
                    var entries = publication.inboxes();
                    if (entries.isEmpty()) return List.of();
                    var ids = selectedIds(sessionId, publication);
                    return entries.stream()
                            .filter(entry -> ids.contains(entry.plugin().identity().id()))
                            .toList();
                });
    }

    /** True when a plugin selected by the session contributes to the given point. */
    public boolean has(@NonNull String sessionId, @NonNull ContributionPoint<?> point) {
        var publication = manager.registry();
        var ids = selectedIds(sessionId, publication);
        return publication.entries(point).stream()
                .anyMatch(entry -> ids.contains(entry.source().namespace()));
    }

    /** True when the session selects the given plugin, resolved through configured aliases. */
    public boolean includes(@NonNull String sessionId, @NonNull String pluginId) {
        var publication = manager.registry();
        String canonical = publication.canonicalId(pluginId);
        return selectedIds(sessionId, publication).contains(canonical);
    }

    /** Keeps only the tool definitions whose provenance plugin is selected by the session. */
    public @NonNull Set<ToolDefinition> tools(
            @NonNull String sessionId, @NonNull Set<ToolDefinition> tools) {
        var ids = selectedIds(sessionId);
        return tools.stream()
                .filter(
                        t -> {
                            var provenance = t.provenance();
                            return provenance == null || ids.contains(provenance.pluginId());
                        })
                .collect(Collectors.toUnmodifiableSet());
    }

    private @NonNull Set<String> selectedIds(@NonNull String sessionId) {
        return selectedIds(sessionId, manager.registry());
    }

    /** Resolves available session pins against the supplied publication without replacing it. */
    public @NonNull Set<String> selectedIds(
            @NonNull String sessionId, @NonNull PluginRegistry publication) {
        return bindings(sessionId).stream()
                .filter(
                        binding ->
                                availability(binding, publication)
                                        == BoundPluginAvailability.AVAILABLE)
                .map(binding -> publication.canonicalId(binding.id()))
                .collect(Collectors.toSet());
    }

    /** Whether the exact selected plugin revision can serve this session now. */
    public boolean available(@NonNull PluginBinding selected) {
        return availability(selected) == BoundPluginAvailability.AVAILABLE;
    }

    /** Explains why an exact pinned plugin can or cannot serve the session now. */
    public @NonNull BoundPluginAvailability availability(@NonNull PluginBinding selected) {
        return availability(selected, manager.registry());
    }

    private @NonNull BoundPluginAvailability availability(
            @NonNull PluginBinding selected, @NonNull PluginRegistry publication) {
        String canonical = publication.canonicalId(selected.id());
        if (publication.disabled().stream().anyMatch(plugin -> plugin.id().equals(canonical)))
            return BoundPluginAvailability.DISABLED;
        if (publication.declined().stream().anyMatch(plugin -> plugin.id().equals(canonical)))
            return BoundPluginAvailability.DECLINED;
        try {
            var installed = publication.plugin(canonical);
            if (installed.state() != PluginState.ACTIVE) return BoundPluginAvailability.INACTIVE;
            if (!selected.version().equals(installed.identity().version())
                    || !selected.revision().equals(installed.binding().revision()))
                return BoundPluginAvailability.REVISION_MISMATCH;
            return BoundPluginAvailability.AVAILABLE;
        } catch (IllegalArgumentException missing) {
            return BoundPluginAvailability.ABSENT;
        }
    }

    /** Current resolution of a preserved session plugin pin. */
    public enum BoundPluginAvailability {
        AVAILABLE,
        ABSENT,
        DISABLED,
        DECLINED,
        REVISION_MISMATCH,
        INACTIVE
    }

    /** Preserved session pin and whether its exact plugin revision is currently usable. */
    public record BoundPluginStatus(
            @NonNull String id,
            @NonNull String version,
            @NonNull String revision,
            boolean available,
            @NonNull BoundPluginAvailability availability) {}

    /** Reports unavailable pins without mutating or locking the session. */
    public @NonNull List<BoundPluginStatus> status(@NonNull String sessionId) {
        var publication = manager.registry();
        return bindings(sessionId).stream()
                .map(
                        binding -> {
                            var availability = availability(binding, publication);
                            return new BoundPluginStatus(
                                    binding.id(),
                                    binding.version(),
                                    binding.revision(),
                                    availability == BoundPluginAvailability.AVAILABLE,
                                    availability);
                        })
                .toList();
    }
}
