package top.focess.veto.integration.plugins;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
import top.focess.veto.bus.DeltaFrame;
import top.focess.veto.bus.SessionInvalidations;
import top.focess.veto.session.SessionService;
import top.focess.veto.util.Nullness;
import top.focess.veto.vault.KeysteadVault;
import top.focess.veto.vault.UserContext;

/** Generic effects; feature interpretation and scheduling belong to their plugins. */
@Configuration(proxyBeanMethods = false)
@NullMarked
public class PluginHostConfiguration {
    private @Nullable DeltaBroker broker;

    @Autowired
    public void attachBroker(DeltaBroker broker) {
        this.broker = broker;
    }

    private final CompletableFuture<Void> ready = new CompletableFuture<>();

    @EventListener(ApplicationReadyEvent.class)
    public void ready() {
        ready.complete(null);
    }

    @Bean
    public PluginHostServices runtimeHostServices(
            ObjectProvider<SessionAgentRegistry> agents,
            ObjectProvider<SessionService> sessions,
            ObjectProvider<KeysteadVault> vault,
            ObjectProvider<SessionInvalidations> invalidations) {
        PluginHost host =
                new PluginHost() {
                    public void whenReady(Runnable callback) {
                        ready.thenRun(callback);
                    }

                    public void await(String tool, PluginAwait wait) {
                        invocation(tool);
                        ToolCallContextHolder.await(wait);
                    }

                    public Invocation invocation(String tool) {
                        var context = ToolCallContextHolder.get();
                        if (context == null)
                            throw new SecurityException("No authorized invocation");
                        CapabilityAccess.require(context.executionPermit().capability(), tool);
                        if (context.owner() == null || context.sessionId() == null)
                            throw new SecurityException("No session scope");
                        return new Invocation(
                                Nullness.requireNonNull(context.owner()),
                                Nullness.requireNonNull(context.sessionId()).toString(),
                                context.agentId(),
                                context.requestId(),
                                context.executionPermit().callId());
                    }

                    public void publish(String session, String topic, JsonValue.ObjectValue facts) {
                        var publisher = broker;
                        if (publisher == null)
                            throw new IllegalStateException("Plugin transport unavailable");
                        var mapper = new ObjectMapper();
                        Map<String, JsonNode> attrs =
                                Map.of(
                                        "topic",
                                        mapper.valueToTree(topic),
                                        "data",
                                        mapper.valueToTree(JsonValues.toMap(facts)));
                        publisher.publish(
                                new DeltaFrame(
                                        UUID.fromString(session),
                                        0,
                                        null,
                                        DeltaFrame.Kind.PLUGIN_EVENT,
                                        "",
                                        attrs));
                    }

                    public void invalidate(String session, String resource) {
                        invalidations.getObject().changed(UUID.fromString(session), resource);
                    }

                    public void wake(String owner, String session, String agent) {
                        if (!vault.getObject().isUnlocked(owner)) return;
                        String previous = UserContext.get();
                        UserContext.set(owner);
                        try {
                            var id = UUID.fromString(session);
                            if (!sessions.getObject().activateForObservation(id, owner, agent))
                                return;
                            agents.getObject().agents(id).stream()
                                    .filter(entry -> entry.agent().id().equals(agent))
                                    .forEach(entry -> entry.agent().signalWork());
                        } finally {
                            if (previous == null) UserContext.clear();
                            else UserContext.set(previous);
                        }
                    }
                };
        return new PluginHostServices(Map.of(PluginHost.class, host));
    }
}
