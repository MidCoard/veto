package top.focess.veto.plugin.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.agent.workflow.PluginAwait;
import top.focess.veto.api.plugin.AbstractVetoPlugin;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginContributions;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.contract.AgentWorkSource;
import top.focess.veto.api.plugin.contract.JsonValue;

class CompositeAgentWorkSourceTest {
    @Test
    void arbitraryPluginOwnsPendingWorkAndCallbacksWithoutBuiltinTypes() throws Exception {
        var calls = new ArrayList<String>();
        var observation =
                new AgentWorkSource.Observation(
                        "local-id",
                        null,
                        "Review incoming result",
                        Instant.now(),
                        "vendor.reminder",
                        Map.of(),
                        "local-episode");
        AgentWorkSource source =
                new AgentWorkSource() {
                    public @NonNull List<Observation> pending(@NonNull Scope scope) {
                        return List.of(observation);
                    }

                    public void started(@NonNull Scope scope, @NonNull Observation value) {
                        calls.add("started:" + value.id());
                    }

                    public void completed(
                            @NonNull Scope scope, @NonNull Observation value, boolean success) {
                        calls.add("completed:" + value.id() + ":" + success);
                    }

                    public void cancelled(@NonNull Scope scope, @NonNull Observation value) {
                        calls.add("cancelled:" + value.id());
                    }
                };
        var plugin =
                new AbstractVetoPlugin() {
                    protected void onStart() {}

                    protected void onClose() {}

                    public @NonNull PluginIdentity identity() {
                        return new PluginIdentity("example.reminders", "1.0.0");
                    }

                    protected @NonNull PluginContributions onInitialize(
                            @NonNull PluginContext context, JsonValue.@NonNull ObjectValue config) {
                        return new PluginContributions(List.of());
                    }
                };
        try (var executor = Executors.newSingleThreadExecutor()) {
            var managed = new ManagedPlugin(plugin, executor);
            try {
                managed.initialize(
                        new PluginContext(
                                plugin.identity(),
                                () -> {},
                                () -> {
                                    throw new IllegalStateException(
                                            "Plugin context is not bound to a lifecycle owner");
                                },
                                Map.of()),
                        new JsonValue.ObjectValue(Map.of()));
                managed.start();
                var signal = new PluginAwait("delivery", new CompletableFuture<Boolean>());
                var awaiting = managed.ownAwait(signal);
                assertSame(awaiting, managed.ownAwait(signal));
                assertEquals(managed.bindingId() + "/delivery", awaiting.token());
                var composite =
                        new CompositeAgentWorkSource(
                                () ->
                                        List.of(
                                                new CompositeAgentWorkSource.Entry(
                                                        "example.reminders:work",
                                                        managed,
                                                        source)));
                var scope = new AgentWorkSource.Scope("session", "agent", null);
                var value = composite.pending(scope).getFirst();
                assertEquals("example.reminders:work/local-id", value.id());
                assertEquals("vendor.reminder", value.topic());
                assertEquals(
                        "plugin-work:22:example.reminders:work:local-episode",
                        value.continuationId());
                var other =
                        new CompositeAgentWorkSource(
                                () ->
                                        List.of(
                                                new CompositeAgentWorkSource.Entry(
                                                        "other.plugin:work", managed, source)));
                assertNotEquals(
                        value.continuationId(), other.pending(scope).getFirst().continuationId());
                assertNotEquals("local-episode", value.continuationId());
                composite.started(scope, value);
                composite.completed(scope, value, true);
                composite.cancelled(scope, value);
                assertEquals(
                        List.of(
                                "started:local-id",
                                "completed:local-id:true",
                                "cancelled:local-id"),
                        calls);
                managed.close();
                assertTrue(awaiting.ready().isCompletedExceptionally());
                assertTrue(signal.ready().isCancelled());
                assertThrows(IllegalStateException.class, () -> composite.pending(scope));
            } finally {
                managed.close();
            }
        }
    }
}
