package top.focess.veto.integration.plugins;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import top.focess.veto.agent.SessionAgentRegistry;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.contract.AgentWorkSource;
import top.focess.veto.builtin.group.GroupObservations;
import top.focess.veto.builtin.group.GroupRegistry;
import top.focess.veto.builtin.monitor.MonitorService;
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
                                                group.owner(),
                                                group.sessionId() == null
                                                        ? null
                                                        : Nullness.requireNonNull(group.sessionId())
                                                                .toString(),
                                                group.leaderId(),
                                                group.state(),
                                                group.dag().nodes()))
                        .toList();
    }

    public static @NonNull AgentWorkSource work(@NonNull MonitorService service) {
        if (mockingDetails(service).isMock()) {
            doCallRealMethod().when(service).pending(any(AgentWorkSource.Scope.class));
            doCallRealMethod().when(service).started(any(), any());
            doCallRealMethod().when(service).completed(any(), any(), anyBoolean());
            doCallRealMethod().when(service).cancelled(any(), any());
        }
        return service;
    }

    public static @NonNull PluginHost host(
            @NonNull SessionService sessions,
            @NonNull SessionAgentRegistry agents,
            @NonNull KeysteadVault vault) {
        var factory = new StaticListableBeanFactory();
        factory.addBean("sessions", sessions);
        factory.addBean("agents", agents);
        factory.addBean("vault", vault);
        @NonNull SessionInvalidations invalidations = mock();
        factory.addBean("invalidations", invalidations);
        return (PluginHost)
                Nullness.requireNonNull(
                        new PluginHostConfiguration()
                                .runtimeHostServices(
                                        factory.getBeanProvider(
                                                ToolDocs.nonNullClass(SessionAgentRegistry.class)),
                                        factory.getBeanProvider(
                                                ToolDocs.nonNullClass(SessionService.class)),
                                        factory.getBeanProvider(
                                                ToolDocs.nonNullClass(KeysteadVault.class)),
                                        factory.getBeanProvider(
                                                ToolDocs.nonNullClass(SessionInvalidations.class)))
                                .services()
                                .get(PluginHost.class));
    }
}
