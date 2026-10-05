package top.focess.veto.integration.plugins;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import top.focess.veto.agent.SessionAgentRegistry;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.contract.AgentInbox;
import top.focess.veto.builtin.group.GroupObservations;
import top.focess.veto.builtin.group.GroupRegistry;
import top.focess.veto.builtin.monitor.MonitorRecord;
import top.focess.veto.builtin.monitor.MonitorService;
import top.focess.veto.bus.DeltaBroker;
import top.focess.veto.bus.SessionInvalidations;
import top.focess.veto.session.SessionService;
import top.focess.veto.util.Nullness;
import top.focess.veto.vault.KeysteadVault;

/** Adapts integration fixtures to the public plugin contracts. No production compatibility shim. */
public final class MonitorTestSupport {
    private MonitorTestSupport() {}

    public static @NonNull GroupObservations groups(@NonNull GroupRegistry registry) {
        return () ->
                registry.snapshot().values().stream()
                        .map(
                                group ->
                                        new GroupObservations.View(
                                                group.groupId().toString(),
                                                group.userId(),
                                                group.sessionId() == null
                                                        ? null
                                                        : Nullness.requireNonNull(group.sessionId())
                                                                .toString(),
                                                group.leaderId(),
                                                group.state(),
                                                group.dag().nodes()))
                        .toList();
    }

    /** Observes one activation without polling while holding the service's synchronized method. */
    public static @NonNull CompletableFuture<Boolean> completion(
            @NonNull MonitorService service,
            @NonNull String agentId,
            MonitorRecord.@NonNull Event event) {
        var completed = new CompletableFuture<Boolean>();
        doAnswer(
                        invocation -> {
                            completed.complete(invocation.getArgument(2));
                            return null;
                        })
                .when(service)
                .activationCompleted(eq(agentId), eq(event), anyBoolean());
        return completed;
    }

    public static @NonNull AgentInbox work(@NonNull MonitorService service) {
        if (mockingDetails(service).isMock()) {
            doCallRealMethod().when(service).pending(any(AgentInbox.InboxContext.class));
            doCallRealMethod().when(service).started(any(), any());
            doCallRealMethod().when(service).completed(any(), any(), anyBoolean());
            doCallRealMethod().when(service).cancelled(any(), any());
        }
        return new AgentInbox() {
            public @NonNull List<Observation> pending(@NonNull InboxContext scope) {
                return service.pending(scope);
            }

            public void started(@NonNull InboxContext scope, @NonNull Observation observation) {
                service.started(scope, observation);
            }

            public void completed(
                    @NonNull InboxContext scope,
                    @NonNull Observation observation,
                    boolean success) {
                service.completed(scope, observation, success);
            }

            public void cancelled(@NonNull InboxContext scope, @NonNull Observation observation) {
                service.cancelled(scope, observation);
            }
        };
    }

    public static @NonNull PluginHost host(
            @NonNull SessionService sessions,
            @NonNull SessionAgentRegistry agents,
            @NonNull KeysteadVault vault) {
        var factory = new StaticListableBeanFactory();
        factory.addBean("sessions", sessions);
        factory.addBean("agents", agents);
        factory.addBean("vault", vault);
        SessionInvalidations invalidations = mock(SessionInvalidations.class);
        factory.addBean("invalidations", invalidations);
        return (PluginHost)
                Nullness.requireNonNull(
                        new PluginHostConfiguration(new DeltaBroker())
                                .runtimeHostServices(
                                        factory.getBeanProvider(SessionAgentRegistry.class),
                                        factory.getBeanProvider(SessionService.class),
                                        factory.getBeanProvider(KeysteadVault.class),
                                        factory.getBeanProvider(SessionInvalidations.class))
                                .services()
                                .get(PluginHost.class));
    }
}
