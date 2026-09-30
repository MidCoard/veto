package top.focess.veto.integration.plugins;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import top.focess.veto.agent.TurnType;
import top.focess.veto.agent.tool.ToolDefinition;
import top.focess.veto.api.agent.control.SourceEvidence;
import top.focess.veto.api.event.WorkflowEvent;
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
import top.focess.veto.session.SessionHistoryLoader;

/**
 * Immutable session selection. Installed packages are a catalog, never a global availability
 * switch.
 */
@Service
public class SessionPlugins {
    private final @NonNull PluginManager manager;
    private final @NonNull SessionRepository sessions;
    private final @NonNull SessionHistoryLoader history;

    /** Creates the selection service over the plugin manager and session stores. */
    public SessionPlugins(
            @NonNull PluginManager manager,
            @NonNull SessionRepository sessions,
            @NonNull SessionHistoryLoader history) {
        this.manager = manager;
        this.sessions = sessions;
        this.history = history;
    }

    /**
     * Resolves the requested ids (null selects every installed plugin) to pinned bindings;
     * duplicates and unknown or inactive plugins are rejected.
     */
    public @NonNull List<PluginBinding> selection(List<String> requested) {
        var available = manager.plugins();
        var ids =
                requested == null
                        ? available.stream().map(p -> p.identity().id()).toList()
                        : requested.stream().map(manager::canonicalId).toList();
        if (ids.size() != Set.copyOf(ids).size())
            throw new IllegalArgumentException("Duplicate plugin selection");
        return ids.stream()
                .map(
                        id -> {
                            var plugin = manager.plugin(id);
                            if (plugin.state() != PluginState.ACTIVE)
                                throw new IllegalArgumentException("Plugin is unavailable: " + id);
                            return binding(plugin);
                        })
                .toList();
    }

    private static @NonNull PluginBinding binding(@NonNull PluginLifecycle plugin) {
        String revision =
                plugin.implementation() instanceof ScriptPlugin script
                        ? script.digest()
                        : plugin.identity().version();
        return new PluginBinding(plugin.identity().id(), plugin.identity().version(), revision);
    }

    /**
     * Returns the session's pinned bindings, migrating legacy sessions from their earliest recorded
     * manifest. Missing or changed plugins remain pinned as opaque history.
     */
    public @NonNull List<PluginBinding> bindings(@NonNull String sessionId) {
        var session =
                sessions.findById(sessionId)
                        .orElseThrow(() -> new IllegalStateException("Session not found"));
        var bindings = session.getPluginBindings();
        if (bindings == null) {
            // Migrate legacy sessions from their earliest recorded manifest, never from a mutable
            // switch.
            var records = history.load(sessionId);
            String original =
                    records.stream()
                            .filter(t -> t.type() == TurnType.AGENT_INIT)
                            .map(t -> String.valueOf(t.payload().get("system_prompt")))
                            .findFirst()
                            .orElse("");
            bindings =
                    manager.plugins().stream()
                            .filter(
                                    p ->
                                            original.isEmpty()
                                                    || manager
                                                            .catalog()
                                                            .entries(
                                                                    StandardContributionPoints
                                                                            .TOOLS)
                                                            .stream()
                                                            .anyMatch(
                                                                    e ->
                                                                            e.source()
                                                                                            .namespace()
                                                                                            .equals(
                                                                                                    p.identity()
                                                                                                            .id())
                                                                                    && (original
                                                                                                    .contains(
                                                                                                            "### `"
                                                                                                                    + manager
                                                                                                                            .toolName(
                                                                                                                                    e)
                                                                                                                    + "`")
                                                                                            || original
                                                                                                    .contains(
                                                                                                            "### `"
                                                                                                                    + e.id().value()
                                                                                                                            .substring(
                                                                                                                                    e.id().value()
                                                                                                                                                    .indexOf(
                                                                                                                                                            ':')
                                                                                                                                            + 1)
                                                                                                                    + "`"))))
                            .map(SessionPlugins::binding)
                            .toList();
            session.setPluginBindings(bindings);
            sessions.saveAndFlush(session);
        }
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
        var entries = manager.catalog().entries(StandardContributionPoints.AGENT_CONFIGURATION);
        if (entries.isEmpty()) return null;
        var selected = selectedIds(session);
        AgentConfiguration.Intent result = null;
        for (var entry : entries) {
            String namespace = entry.source().namespace();
            if (!selected.contains(namespace)
                    || (configurationOwner != null && !configurationOwner.equals(namespace)))
                continue;
            if (manager.plugin(namespace).state() != PluginState.ACTIVE) continue;
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
                        manager.plugin(namespace)
                                .execute(
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

    /**
     * Dispatches a workflow event to the session's selected listeners in priority order, each under
     * its contributing plugin's admission. With no contributed listener the event is returned
     * unchanged.
     */
    public void dispatch(@NonNull WorkflowEvent event) {
        if (manager.catalog().entries(StandardContributionPoints.LISTENERS).isEmpty()) return;
        manager.events().submit(event, selectedIds(event.sessionId()));
    }

    /** Opens the model-response policies of the session's selected plugins in catalog order. */
    public @NonNull List<ModelResponsePolicy.Exchange> responsePolicies(@NonNull String sessionId) {
        var ids = selectedIds(sessionId);
        List<ModelResponsePolicy.Exchange> result = new ArrayList<>();
        for (var entry : manager.catalog().entries(StandardContributionPoints.MODEL_RESPONSE)) {
            if (!ids.contains(entry.source().namespace())) continue;
            var plugin = manager.plugin(entry.source().namespace());
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
                    if (manager.catalog().entries(StandardContributionPoints.AGENT_INBOX).isEmpty())
                        return List.of();
                    var ids = selectedIds(sessionId);
                    return manager
                            .catalog()
                            .entries(StandardContributionPoints.AGENT_INBOX)
                            .stream()
                            .filter(entry -> ids.contains(entry.source().namespace()))
                            .map(
                                    entry ->
                                            new CompositeAgentInbox.Entry(
                                                    entry.id().value(),
                                                    manager.plugin(entry.source().namespace()),
                                                    entry.implementation()))
                            .toList();
                });
    }

    /** True when a plugin selected by the session contributes to the given point. */
    public boolean has(@NonNull String sessionId, @NonNull ContributionPoint<?> point) {
        var ids = selectedIds(sessionId);
        return manager.catalog().entries(point).stream()
                .anyMatch(entry -> ids.contains(entry.source().namespace()));
    }

    /** True when the session selects the given plugin, resolved through historical aliases. */
    public boolean includes(@NonNull String sessionId, @NonNull String pluginId) {
        String canonical = manager.canonicalId(pluginId);
        return selectedIds(sessionId).contains(canonical);
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
        return bindings(sessionId).stream()
                .filter(this::available)
                .map(binding -> manager.canonicalId(binding.id()))
                .collect(Collectors.toSet());
    }

    /** Whether the exact selected plugin revision can serve this session now. */
    public boolean available(@NonNull PluginBinding selected) {
        return availability(selected) == BoundPluginAvailability.AVAILABLE;
    }

    /** Explains why an exact pinned plugin can or cannot serve the session now. */
    public @NonNull BoundPluginAvailability availability(@NonNull PluginBinding selected) {
        if (manager.isDisabled(selected.id())) return BoundPluginAvailability.DISABLED;
        if (manager.isDeclined(selected.id())) return BoundPluginAvailability.DECLINED;
        try {
            var installed = manager.plugin(selected.id());
            if (installed.state() != PluginState.ACTIVE) return BoundPluginAvailability.INACTIVE;
            if (!selected.version().equals(installed.identity().version())
                    || !selected.revision().equals(binding(installed).revision()))
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
        return bindings(sessionId).stream()
                .map(
                        binding -> {
                            var availability = availability(binding);
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
