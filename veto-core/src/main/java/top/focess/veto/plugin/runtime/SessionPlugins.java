package top.focess.veto.plugin.runtime;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import top.focess.veto.agent.TurnType;
import top.focess.veto.agent.tool.ToolDefinition;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.contract.PluginFailure;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contract.TextProtection;
import top.focess.veto.api.plugin.contract.WorkflowHook;
import top.focess.veto.api.plugin.contribution.ContributionPoint;
import top.focess.veto.model.SessionRepository;
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
            if (!binding.equals(binding(manager.plugin(binding.id()))))
                throw new IllegalStateException(
                        "Session requires the pinned plugin revision: " + binding.id());
        }
        return bindings;
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
