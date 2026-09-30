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
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginContributions;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.VetoPlugin;
import top.focess.veto.api.plugin.contract.AgentInbox;
import top.focess.veto.api.plugin.contract.JsonValue;

class CompositeAgentInboxTest {
    @Test
    void arbitraryPluginOwnsPendingWorkAndCallbacksWithoutBuiltinTypes() throws Exception {
        var calls = new ArrayList<String>();
        var observation =
                new AgentInbox.Observation(
                        "local-id",
                        null,
                        "Review incoming result",
                        Instant.now(),
                        "vendor.reminder",
                        Map.of(),
                        "local-episode");
        AgentInbox source =
                new AgentInbox() {
                    public @NonNull List<Observation> pending(@NonNull InboxContext scope) {
                        return List.of(observation);
                    }

                    public void started(@NonNull InboxContext scope, @NonNull Observation value) {
                        calls.add("started:" + value.id());
                    }

                    public void completed(
                            @NonNull InboxContext scope,
                            @NonNull Observation value,
                            boolean success) {
                        calls.add("completed:" + value.id() + ":" + success);
                    }

                    public void cancelled(@NonNull InboxContext scope, @NonNull Observation value) {
                        calls.add("cancelled:" + value.id());
                    }
                };
        var plugin =
                new VetoPlugin() {
                    @Override
                    public @NonNull PluginContributions contributions() {
                        return new PluginContributions(List.of());
                    }

                    public void start() {}

                    public void close() {}

                    public @NonNull PluginIdentity identity() {
                        return new PluginIdentity("example.reminders", "1.0.0");
                    }
                };
        try (var executor = Executors.newSingleThreadExecutor()) {
            var managed = new PluginLifecycle(plugin, executor);
            try {
                managed.initialize(
                        new PluginContext(
                                plugin.identity(),
                                () -> {},
                                () -> {
                                    throw new IllegalStateException(
                                            "Plugin context is not bound to a lifecycle owner");
                                },
                                Map.of(),
                                Map.of()),
                        new JsonValue.ObjectValue(Map.of()));
                managed.start();
                var signal = new PluginAwait("delivery", new CompletableFuture<Boolean>());
                var awaiting = managed.ownAwait(signal);
                assertSame(awaiting, managed.ownAwait(signal));
                assertEquals(managed.bindingId() + "/delivery", awaiting.token());
                var composite =
                        new CompositeAgentInbox(
                                () ->
                                        List.of(
                                                new CompositeAgentInbox.Entry(
                                                        "example.reminders:work",
                                                        managed,
                                                        source)));
                var scope = new AgentInbox.InboxContext("session", "agent", null);
                var value = composite.pending(scope).getFirst();
                assertEquals("example.reminders:work/local-id", value.id());
                assertEquals("vendor.reminder", value.topic());
                assertEquals(
                        "plugin-work:22:example.reminders:work:local-episode",
                        value.continuationId());
                var other =
                        new CompositeAgentInbox(
                                () ->
                                        List.of(
                                                new CompositeAgentInbox.Entry(
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
