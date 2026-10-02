package top.focess.veto.integration.plugins;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.AgentService;
import top.focess.veto.agent.SessionAgentRegistry;
import top.focess.veto.agent.VetoAgent;
import top.focess.veto.agent.capability.CapabilityAccess;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.api.agent.AgentResult;
import top.focess.veto.api.agent.AgentState;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.agent.AgentHost;
import top.focess.veto.api.plugin.agent.AgentProfile;
import top.focess.veto.api.plugin.agent.IsolatedAgent;
import top.focess.veto.api.plugin.storage.PluginStorage;
import top.focess.veto.integration.plugins.storage.PluginStorageFactory;
import top.focess.veto.model.AgentEntity;
import top.focess.veto.model.AgentInstanceRepository;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.plugin.runtime.PluginLifecycle;
import top.focess.veto.session.SessionHistoryLoader;
import top.focess.veto.vault.KeysteadVault;
import top.focess.veto.vault.UserContext;

/**
 * Per-plugin, session-bound child execution. No feature policy lives here.
 *
 * <p>Child opening excludes concurrent ownership checks and creation for the same UUID using a
 * fixed set of identity stripes. Different stripes can open concurrently; hash collisions share
 * exclusion. Stripes are never removed or replaced. Registry operations retain their own monitor,
 * and plugin resource registration happens after the child identity exclusion is released.
 */
@Component
public final class PluginAgentHosts implements PluginAgentHostFactory {
    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.integration.plugins.PluginAgentHosts");

    private final @NonNull ObjectProvider<AgentService> service;
    private final @NonNull SessionRepository sessions;
    private final @NonNull AgentInstanceRepository identities;
    private final @NonNull SessionAgentRegistry registry;
    private final @NonNull PluginStorageFactory scopes;
    private final @NonNull SessionHistoryLoader history;
    private final @NonNull KeysteadVault vault;
    private final @NonNull List<@NonNull Object> childLocks =
            IntStream.range(0, 64).mapToObj(index -> new Object()).toList();

    /** Creates the factory over the agent, session, identity, history, and vault services. */
    public PluginAgentHosts(
            @NonNull ObjectProvider<AgentService> service,
            @NonNull SessionRepository sessions,
            @NonNull AgentInstanceRepository identities,
            @NonNull SessionAgentRegistry registry,
            @NonNull PluginStorageFactory scopes,
            @NonNull SessionHistoryLoader history,
            @NonNull KeysteadVault vault) {
        this.service = service;
        this.sessions = sessions;
        this.identities = identities;
        this.registry = registry;
        this.scopes = scopes;
        this.history = history;
        this.vault = vault;
    }

    private @NonNull Supplier<@Nullable IsolatedExecutions> isolated = () -> null;

    /** Attaches the optional isolated-execution engine used by {@code AgentHost.isolate}. */
    @Autowired
    public void attachIsolatedProvider(@NonNull ObjectProvider<IsolatedExecutions> value) {
        isolated = value::getIfAvailable;
    }

    void attachIsolated(@NonNull IsolatedExecutions value) {
        isolated = () -> value;
    }

    /** Exposes this factory as a host service so the manager can bind it per plugin. */
    @Bean
    public @NonNull PluginHostServices agentHostServices() {
        return new PluginHostServices(Map.of(PluginAgentHostFactory.class, this));
    }

    /** Returns the agent host for one plugin activation; every effect re-authorizes its session. */
    public @NonNull AgentHost bind(
            @NonNull PluginLifecycle plugin, @NonNull PluginStorage storage) {
        return new AgentHost() {
            public @NonNull Session session(
                    PluginStorage.@NonNull Grant<Scope.@NonNull SessionScope> grant) {
                scopes.authorizeSession(storage, grant);
                return new Session() {
                    public @NonNull String id() {
                        return grant.scope().session();
                    }

                    public @NonNull Child open(
                            @NonNull String id,
                            @NonNull String parentId,
                            @NonNull AgentProfile profile) {
                        return openChild(plugin, storage, grant, id, parentId, profile);
                    }
                };
            }

            public @NonNull IsolatedAgent isolate(
                    IsolatedAgent.@NonNull Spec spec, IsolatedAgent.@NonNull Factory factory) {
                var engine = isolated.get();
                if (engine == null)
                    throw new IllegalStateException("Isolated execution unavailable");
                var call = ToolCallContextHolder.get();
                if (call == null
                        || !plugin.bindingId().equals(call.executionPermit().remoteServerName()))
                    throw new SecurityException("Invocation does not belong to this plugin");
                CapabilityAccess.require(call.executionPermit().capability());
                var grant = storage.currentSession();
                String owner = scopes.authorizeSession(storage, grant);
                var sessionId = call.sessionId();
                if (sessionId == null
                        || !grant.scope().session().equals(sessionId.toString())
                        || !owner.equals(call.owner()))
                    throw new SecurityException("Invocation scope mismatch");
                var child =
                        engine.open(
                                spec,
                                factory,
                                () -> {
                                    try {
                                        return plugin.state() == PluginState.ACTIVE
                                                && vault.isUnlocked(owner)
                                                && owner.equals(
                                                        scopes.authorizeSession(storage, grant));
                                    } catch (RuntimeException failure) {
                                        return false;
                                    }
                                });
                child.onClosed(() -> plugin.releaseResource(child));
                try {
                    plugin.ownStoppingResource(child, child::close);
                } catch (RuntimeException stopped) {
                    child.close();
                    throw stopped;
                }
                return child;
            }
        };
    }

    private AgentHost.@NonNull Child openChild(
            @NonNull PluginLifecycle plugin,
            @NonNull PluginStorage storage,
            PluginStorage.@NonNull Grant<Scope.@NonNull SessionScope> grant,
            @NonNull String id,
            @NonNull String parentId,
            @NonNull AgentProfile profile) {
        UUID parsedId = UUID.fromString(id);
        log.debug("Opening plugin child agent id={}", parsedId);
        if (id.equals(parentId)) throw new SecurityException("Child cannot replace its parent");
        VetoAgent agent;
        synchronized (childLocks.get(parsedId.hashCode() & (childLocks.size() - 1))) {
            // Reauthorize after acquiring exclusion: scopes and vault access can change while
            // waiting.
            String owner = scopes.authorizeSession(storage, grant);
            if (!vault.isUnlocked(owner)) throw new SecurityException("Session owner is locked");
            var session = sessions.findById(grant.scope().session()).orElseThrow();
            var parent =
                    identities
                            .findById(parentId)
                            .orElseThrow(() -> new SecurityException("Unknown parent"));
            if (!parent.getSessionId().equals(session.getId()))
                throw new SecurityException("Parent scope mismatch");
            String namespace = plugin.identity().id();
            if (!parentId.equals(session.getPrimaryAgentId())
                    && !namespace.equals(parent.getPluginNamespace()))
                throw new SecurityException("Parent belongs to another plugin");
            var row = identities.findById(id).orElse(null);
            if (row != null
                    && (!row.getSessionId().equals(session.getId())
                            || !namespace.equals(row.getPluginNamespace())
                            || row.isEphemeral()
                            || row.getRole() != AgentEntity.Role.SUB
                            || !parentId.equals(row.getParentAgentId())))
                throw new SecurityException("Agent identity belongs to another scope");
            var live =
                    registry.agents(UUID.fromString(session.getId())).stream()
                            .filter(entry -> entry.agent().id().equals(id))
                            .findFirst()
                            .orElse(null);
            if (live != null) {
                agent = live.agent();
            } else {
                if (row == null) {
                    row = AgentEntity.spawned(id, session.getId(), profile.name());
                    row.claimPlugin(namespace, parentId);
                    identities.saveAndFlush(row);
                }
                String previous = UserContext.get();
                UserContext.set(owner);
                try {
                    agent =
                            service.getObject()
                                    .openPluginAgent(
                                            session,
                                            id,
                                            parentId,
                                            namespace,
                                            profile,
                                            history.load(session.getId(), id));
                } finally {
                    if (previous == null) UserContext.clear();
                    else UserContext.set(previous);
                }
            }
        }
        return child(plugin, storage, grant, agent);
    }

    private void authorizeRelease(
            @NonNull PluginLifecycle plugin,
            @NonNull PluginStorage storage,
            PluginStorage.@NonNull Grant<Scope.@NonNull SessionScope> grant) {
        if (!plugin.cleaningResources()) scopes.authorizeSession(storage, grant);
    }

    private AgentHost.@NonNull Child child(
            @NonNull PluginLifecycle plugin,
            @NonNull PluginStorage storage,
            PluginStorage.@NonNull Grant<Scope.@NonNull SessionScope> grant,
            @NonNull VetoAgent agent) {
        Runnable release =
                () -> {
                    if (!registry.stopIfSame(agent.id(), agent)) agent.terminate();
                };
        boolean owned;
        try {
            owned = plugin.ownResource(agent, release);
        } catch (RuntimeException stopped) {
            release.run();
            throw stopped;
        }
        if (owned) agent.onTermination(() -> plugin.releaseResource(agent));
        return new AgentHost.Child() {
            public @NonNull String id() {
                return agent.id();
            }

            public @NonNull AgentState state() {
                return agent.state();
            }

            public AgentHost.@NonNull Request submit(@NonNull String prompt) {
                String owner = scopes.authorizeSession(storage, grant);
                if (!vault.isUnlocked(owner))
                    throw new SecurityException("Session owner is locked");
                var request = agent.submitRequest(prompt);
                return new AgentHost.Request() {
                    public @NonNull String id() {
                        return request.requestId();
                    }

                    public @NonNull CompletableFuture<AgentResult> result() {
                        return request.result().copy();
                    }

                    public @NonNull CompletableFuture<Boolean> settled() {
                        return request.settled().copy();
                    }

                    public boolean cancel(@NonNull Duration timeout) throws InterruptedException {
                        authorizeRelease(plugin, storage, grant);
                        return agent.cancelTask(request.result(), timeout);
                    }
                };
            }

            public void close() {
                authorizeRelease(plugin, storage, grant);
                release.run();
                plugin.releaseResource(agent);
            }

            public boolean awaitTermination(@NonNull Duration timeout) throws InterruptedException {
                return agent.awaitTermination(timeout);
            }
        };
    }
}
