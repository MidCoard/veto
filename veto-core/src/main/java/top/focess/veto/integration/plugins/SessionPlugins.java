package top.focess.veto.integration.plugins;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import top.focess.veto.agent.TurnType;
import top.focess.veto.agent.tool.ToolDefinition;
import top.focess.veto.api.agent.control.SourceEvidence;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.llm.VetoResponse;
import top.focess.veto.api.plugin.PluginBinding;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.agent.AgentHost;
import top.focess.veto.api.plugin.agent.AgentProfile;
import top.focess.veto.api.plugin.contract.AgentConfiguration;
import top.focess.veto.api.plugin.contract.AgentWorkSource;
import top.focess.veto.api.plugin.contract.ModelResponsePolicy;
import top.focess.veto.api.plugin.contract.PluginFailure;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contract.TextProtection;
import top.focess.veto.api.plugin.contract.WorkflowHook;
import top.focess.veto.api.plugin.contribution.ContributionPoint;
import top.focess.veto.api.plugin.storage.PluginStorage;
import top.focess.veto.integration.plugins.storage.PluginInvocationScope;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.plugin.runtime.*;
import top.focess.veto.plugin.runtime.CompositeAgentWorkSource;
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

    public SessionPlugins(
            @NonNull PluginManager manager,
            @NonNull SessionRepository sessions,
            @NonNull SessionHistoryLoader history) {
        this.manager = manager;
        this.sessions = sessions;
        this.history = history;
    }

    public @NonNull List<PluginBinding> selection(@Nullable List<String> requested) {
        var available = manager.plugins();
        var ids =
                requested == null
                        ? available.stream().map(p -> p.identity().id()).toList()
                        : requested;
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

    private static @NonNull PluginBinding binding(@NonNull ManagedPlugin plugin) {
        String revision =
                plugin.implementation() instanceof ScriptPlugin script
                        ? script.digest()
                        : plugin.identity().version();
        return new PluginBinding(plugin.identity().id(), plugin.identity().version(), revision);
    }

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
        for (var binding : bindings) {
            var installed =
                    manager.plugins().stream()
                            .filter(plugin -> plugin.identity().id().equals(binding.id()))
                            .findFirst()
                            .orElse(null);
            if (installed != null && !binding.equals(binding(installed)))
                throw new IllegalStateException(
                        "Session requires the pinned plugin revision: " + binding.id());
        }
        return bindings;
    }

    public AgentConfiguration.@Nullable Intent configure(
            @NonNull String owner,
            @NonNull String session,
            @NonNull String agent,
            @Nullable String configurationOwner,
            @NonNull AgentProfile base,
            @NonNull List<AgentConfiguration.Tool> tools,
            @NonNull String activeTask) {
        var entries = manager.catalog().entries(StandardContributionPoints.AGENT_CONFIGURATION);
        if (entries.isEmpty()) return null;
        var selected =
                bindings(session).stream().map(PluginBinding::id).collect(Collectors.toSet());
        AgentConfiguration.Intent result = null;
        for (var entry : entries) {
            String namespace = entry.source().namespace();
            if (!selected.contains(namespace)
                    || (configurationOwner != null && !configurationOwner.equals(namespace)))
                continue;
            if (manager.plugin(namespace).state() != PluginState.ACTIVE) continue;
            var storage =
                    manager.hostService(namespace, ToolDocs.nonNullClass(PluginStorage.class));
            var host = manager.hostService(namespace, ToolDocs.nonNullClass(AgentHost.class));
            if (storage == null || host == null)
                throw new IllegalStateException("Agent configuration services unavailable");
            var invocation = new PluginInvocationScope(owner, session);
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

    @FunctionalInterface
    public interface WorkflowOperation<T extends @NonNull Object> {
        @NonNull T apply(@NonNull WorkflowHook hook, @NonNull T current) throws PluginFailure;
    }

    /** Selected, pinned hooks execute in contribution order with lifecycle admission. */
    public <T extends @NonNull Object> @NonNull T workflow(
            WorkflowHook.@NonNull Context scope,
            @NonNull T initial,
            @NonNull WorkflowOperation<T> operation) {
        var entries = manager.catalog().entries(StandardContributionPoints.WORKFLOW);
        if (entries.isEmpty()) return initial;
        var ids =
                bindings(scope.sessionId()).stream()
                        .map(PluginBinding::id)
                        .collect(Collectors.toSet());
        T result = initial;
        for (var entry : entries) {
            if (!ids.contains(entry.source().namespace())) continue;
            T current = result;
            try {
                scope.cancellation().checkCancelled();
                result =
                        manager.plugin(entry.source().namespace())
                                .execute(
                                        () -> {
                                            T transformed =
                                                    operation.apply(
                                                            entry.implementation(), current);
                                            scope.cancellation().checkCancelled();
                                            return transformed;
                                        });
            } catch (PluginFailure | RuntimeException failure) {
                // Plugin messages may contain raw inputs. Do not propagate them into history.
                throw new IllegalStateException("Workflow hook unavailable");
            }
        }
        return result;
    }

    public @NonNull List<ModelResponsePolicy.Exchange> responsePolicies(@NonNull String sessionId) {
        var ids = bindings(sessionId).stream().map(PluginBinding::id).collect(Collectors.toSet());
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

                            public ModelResponsePolicy.@Nullable Result rejected(int count) {
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

    public @NonNull String protect(
            @NonNull ContributionPoint<? extends TextProtection> point,
            TextProtection.@NonNull Scope scope,
            @NonNull String text) {
        var ids =
                bindings(scope.sessionId()).stream()
                        .map(PluginBinding::id)
                        .collect(Collectors.toSet());
        String result = text;
        for (var entry : manager.catalog().entries(point)) {
            if (!ids.contains(entry.source().namespace())) continue;
            String input = result;
            try {
                result =
                        manager.plugin(entry.source().namespace())
                                .execute(
                                        () ->
                                                entry.implementation()
                                                        .transform(
                                                                scope,
                                                                UUID.randomUUID().toString(),
                                                                input));
            } catch (PluginFailure failure) {
                throw new IllegalStateException("Session text protection unavailable", failure);
            }
        }
        return result;
    }

    public @NonNull AgentWorkSource workSource(@NonNull String sessionId) {
        return new CompositeAgentWorkSource(
                () -> {
                    if (manager.catalog().entries(StandardContributionPoints.AGENT_WORK).isEmpty())
                        return List.of();
                    var ids =
                            bindings(sessionId).stream()
                                    .map(PluginBinding::id)
                                    .collect(Collectors.toSet());
                    return manager.catalog().entries(StandardContributionPoints.AGENT_WORK).stream()
                            .filter(entry -> ids.contains(entry.source().namespace()))
                            .map(
                                    entry ->
                                            new CompositeAgentWorkSource.Entry(
                                                    entry.id().value(),
                                                    manager.plugin(entry.source().namespace()),
                                                    entry.implementation()))
                            .toList();
                });
    }

    public boolean has(@NonNull String sessionId, @NonNull ContributionPoint<?> point) {
        var ids = bindings(sessionId).stream().map(PluginBinding::id).collect(Collectors.toSet());
        return manager.catalog().entries(point).stream()
                .anyMatch(entry -> ids.contains(entry.source().namespace()));
    }

    public boolean includes(@NonNull String sessionId, @NonNull String pluginId) {
        return bindings(sessionId).stream().anyMatch(p -> p.id().equals(pluginId));
    }

    public @NonNull Set<ToolDefinition> tools(
            @NonNull String sessionId, @NonNull Set<ToolDefinition> tools) {
        var ids = bindings(sessionId).stream().map(PluginBinding::id).collect(Collectors.toSet());
        return tools.stream()
                .filter(
                        t -> {
                            var provenance = t.provenance();
                            return provenance == null || ids.contains(provenance.pluginId());
                        })
                .collect(Collectors.toUnmodifiableSet());
    }
}
