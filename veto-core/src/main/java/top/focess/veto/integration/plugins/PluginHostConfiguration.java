package top.focess.veto.integration.plugins;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import top.focess.veto.agent.SessionAgentRegistry;
import top.focess.veto.agent.capability.CapabilityAccess;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.api.agent.workflow.PluginAwait;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.JsonValues;
import top.focess.veto.bus.DeltaBroker;
import top.focess.veto.bus.SessionInvalidations;
import top.focess.veto.contract.EventFrame;
import top.focess.veto.session.SessionService;
import top.focess.veto.util.Nullness;
import top.focess.veto.vault.ExecutionSecurity;
import top.focess.veto.vault.KeysteadVault;

/** Generic effects; feature interpretation and scheduling belong to their plugins. */
@Configuration(proxyBeanMethods = false)
public class PluginHostConfiguration {
    private final @NonNull DeltaBroker broker;

    /** Creates the host adapters with their session event transport. */
    public PluginHostConfiguration(@NonNull DeltaBroker broker) {
        this.broker = broker;
    }

    private final @NonNull CompletableFuture<Void> ready = new CompletableFuture<>();

    /** Releases callbacks registered through {@code PluginHost.whenReady} once startup finished. */
    @EventListener(ApplicationReadyEvent.class)
    @SuppressWarnings(
            "DataFlowIssue") // WHY: a CompletableFuture<Void> can only be completed with null
    public void ready() {
        ready.complete(null);
    }

    /** Provides the generic runtime {@code PluginHost} (invocation, publish, invalidate, wake). */
    @Bean
    public @NonNull PluginHostServices runtimeHostServices(
            @NonNull ObjectProvider<@NonNull SessionAgentRegistry> agents,
            @NonNull ObjectProvider<@NonNull SessionService> sessions,
            @NonNull ObjectProvider<@NonNull KeysteadVault> vault,
            @NonNull ObjectProvider<@NonNull SessionInvalidations> invalidations) {
        PluginHost host =
                new PluginHost() {
                    public void whenReady(@NonNull Runnable callback) {
                        ready.thenRun(callback);
                    }

                    public void await(@NonNull String tool, @NonNull PluginAwait wait) {
                        invocation(tool);
                        ToolCallContextHolder.await(wait);
                    }

                    public @NonNull Invocation invocation(@NonNull String tool) {
                        var context = ToolCallContextHolder.get();
                        if (context == null)
                            throw new SecurityException("No authorized invocation");
                        CapabilityAccess.require(context.executionPermit().capability(), tool);
                        if (context.userId() == null || context.sessionId() == null)
                            throw new SecurityException("No session scope");
                        return new Invocation(
                                Nullness.requireNonNull(context.userId()),
                                Nullness.requireNonNull(context.sessionId()).toString(),
                                context.agentId(),
                                context.requestId(),
                                context.executionPermit().callId());
                    }

                    public void publish(
                            @NonNull String session,
                            @NonNull String topic,
                            JsonValue.@NonNull ObjectValue facts) {
                        var mapper = new ObjectMapper();
                        Map<String, JsonNode> attrs =
                                Map.of(
                                        "topic",
                                        mapper.valueToTree(topic),
                                        "data",
                                        mapper.valueToTree(JsonValues.toMap(facts)));
                        broker.publish(
                                new EventFrame(
                                        UUID.fromString(session),
                                        0,
                                        null,
                                        EventFrame.Kind.PLUGIN_EVENT,
                                        "",
                                        attrs));
                    }

                    public void invalidate(@NonNull String session, @NonNull String resource) {
                        invalidations.getObject().changed(UUID.fromString(session), resource);
                    }

                    public void wake(
                            @NonNull UUID userId, @NonNull String session, @NonNull String agent) {
                        if (!vault.getObject().isUnlocked(userId)) return;
                        var security = ExecutionSecurity.open(userId);
                        try {
                            var id = UUID.fromString(session);
                            if (!sessions.getObject().activateForObservation(id, userId, agent))
                                return;
                            agents.getObject().agents(id).stream()
                                    .filter(entry -> entry.agent().id().equals(agent))
                                    .forEach(entry -> entry.agent().signalWork());
                        } finally {
                            security.close();
                        }
                    }
                };
        return new PluginHostServices(Map.of(PluginHost.class, host));
    }
}
