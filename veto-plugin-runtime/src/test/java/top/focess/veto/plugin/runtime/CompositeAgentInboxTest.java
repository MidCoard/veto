package top.focess.veto.plugin.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.agent.workflow.PluginAwait;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.VetoPlugin;
import top.focess.veto.api.plugin.contract.AgentInbox;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.plugin.runtime.CompositeAgentInbox.Entry;

class CompositeAgentInboxTest {
    private static final class Owner implements AutoCloseable {
        private final @NonNull ExecutorService executor = Executors.newSingleThreadExecutor();
        private final @NonNull ManagedPlugin plugin;

        private Owner() throws Exception {
            var implementation =
                    new VetoPlugin() {
                        public void start() {}

                        public void close() {}

                        public @NonNull PluginIdentity identity() {
                            return new PluginIdentity("example.reminders", "1.0.0");
                        }
                    };
            plugin = new ManagedPlugin(implementation, executor);
            plugin.construct(
                    new PluginContext(
                            implementation.identity(),
                            () -> {},
                            () -> {
                                throw new IllegalStateException("No service scope");
                            },
                            Map.of(),
                            Map.of()),
                    new JsonValue.ObjectValue(Map.of()));
            plugin.start();
        }

        @Override
        public void close() {
            try {
                plugin.close();
            } finally {
                executor.close();
            }
        }
    }

    private static final class Source implements AgentInbox {
        private final @NonNull Observation observation;
        private final @NonNull AtomicInteger completions = new AtomicInteger();
        private final CountDownLatch entered;
        private final CountDownLatch release;

        private Source(
                @NonNull Observation observation, CountDownLatch entered, CountDownLatch release) {
            this.observation = observation;
            this.entered = entered;
            this.release = release;
        }

        public @NonNull List<@NonNull Observation> pending(@NonNull InboxContext scope) {
            return List.of(observation);
        }

        public void started(@NonNull InboxContext scope, @NonNull Observation value) {
            var entering = entered;
            var exiting = release;
            if (entering == null || exiting == null) return;
            entering.countDown();
            try {
                if (!exiting.await(5, TimeUnit.SECONDS))
                    throw new AssertionError("Callback not released");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new AssertionError(error);
            }
        }

        public void completed(
                @NonNull InboxContext scope, @NonNull Observation value, boolean success) {
            completions.incrementAndGet();
        }

        public void cancelled(@NonNull InboxContext scope, @NonNull Observation value) {}
    }

    @Test
    void equalLiveObservationsKeepExactOwnersWhileRestartRepollsStableDurableKeys()
            throws Exception {
        var raw =
                new AgentInbox.Observation(
                        "local-id",
                        null,
                        "work",
                        Instant.now(),
                        "topic",
                        Map.of(),
                        "durable-request");
        var firstSource = new Source(raw, null, null);
        var secondSource = new Source(raw, null, null);
        try (var first = new Owner();
                var second = new Owner()) {
            var firstEntry = new Entry("example.reminders:work", first.plugin, firstSource);
            var secondEntry = new Entry("example.reminders:work", second.plugin, secondSource);
            var selected = new AtomicReference<@NonNull List<@NonNull Entry>>(List.of(firstEntry));
            var composite = new CompositeAgentInbox(selected::get);
            var scope = new AgentInbox.InboxContext("session", "agent", null);
            var original = composite.pending(scope).getFirst();
            selected.set(List.of(secondEntry));
            var replacement = composite.pending(scope).getFirst();
            assertEquals(original, replacement);
            assertNotSame(original, replacement);
            assertThrows(
                    IllegalStateException.class, () -> composite.completed(scope, original, true));
            assertEquals(0, firstSource.completions.get());
            assertEquals(0, secondSource.completions.get());
            composite.completed(scope, replacement, true);
            assertEquals(1, secondSource.completions.get());
            selected.set(List.of());
            assertThrows(IllegalStateException.class, () -> composite.started(scope, replacement));
            selected.set(List.of(secondEntry));
            var reconstructed =
                    new AgentInbox.Observation(
                            replacement.id(),
                            replacement.requestId(),
                            replacement.content(),
                            replacement.occurredAt(),
                            replacement.topic(),
                            replacement.attributes(),
                            replacement.continuationId());
            assertThrows(
                    IllegalStateException.class,
                    () -> composite.completed(scope, reconstructed, true));
            var restarted = new CompositeAgentInbox(selected::get);
            var repolled = restarted.pending(scope).getFirst();
            assertEquals(original.id(), repolled.id());
            assertEquals(original.continuationId(), repolled.continuationId());
            restarted.completed(scope, repolled, true);
            assertEquals(2, secondSource.completions.get());
            assertThrows(IllegalStateException.class, () -> composite.cancelled(scope, original));
        }
    }

    @Test
    void blockedCallbackDoesNotLockOutConcurrentPending() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var raw =
                new AgentInbox.Observation(
                        "local", null, "work", Instant.now(), "topic", Map.of(), null);
        var source = new Source(raw, entered, release);
        try (var userId = new Owner();
                var workers = Executors.newFixedThreadPool(2)) {
            var composite =
                    new CompositeAgentInbox(
                            () ->
                                    List.of(
                                            new Entry(
                                                    "example.reminders:work",
                                                    userId.plugin,
                                                    source)));
            var scope = new AgentInbox.InboxContext("session", "agent", null);
            var value = composite.pending(scope).getFirst();
            var callback = workers.submit(() -> composite.started(scope, value));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var poll = workers.submit(() -> composite.pending(scope));
                assertEquals(1, poll.get(2, TimeUnit.SECONDS).size());
            } finally {
                release.countDown();
            }
            callback.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
    }

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

                    public void start() {}

                    public void close() {}

                    public @NonNull PluginIdentity identity() {
                        return new PluginIdentity("example.reminders", "1.0.0");
                    }
                };
        try (var executor = Executors.newSingleThreadExecutor()) {
            var managed = new ManagedPlugin(plugin, executor);
            try {
                managed.construct(
                        new PluginContext(
                                plugin.identity(),
                                () -> {},
                                () -> {
                                    throw new IllegalStateException(
                                            "Plugin context is not bound to a lifecycle userId");
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
