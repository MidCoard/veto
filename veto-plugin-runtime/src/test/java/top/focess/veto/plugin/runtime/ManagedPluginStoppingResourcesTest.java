package top.focess.veto.plugin.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import top.focess.veto.api.plugin.AbstractVetoPlugin;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginContributions;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.PluginFailure;

@Timeout(10)
class ManagedPluginStoppingResourcesTest {
    @Test
    void releasesBeforeDrainOnceAndRejectsNewResourcesWhileStopping() throws Exception {
        var plugin = new TestPlugin();
        var released = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        var drain = new CompletableFuture<Boolean>();
        var early = new AtomicInteger();
        var duplicate = new AtomicInteger();
        try (var lifecycle = Executors.newSingleThreadExecutor();
                var calls = Executors.newVirtualThreadPerTaskExecutor()) {
            var managed = start(plugin, lifecycle);
            var identity = new Object();
            managed.ownStoppingResource(
                    identity,
                    () -> {
                        early.incrementAndGet();
                        released.countDown();
                    });
            managed.ownStoppingResource(identity, duplicate::incrementAndGet);
            var result =
                    calls.submit(
                            () ->
                                    managed.execute(
                                            () -> {
                                                entered.countDown();
                                                return drain.join();
                                            }));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                var closed = calls.submit(managed::close);
                assertTrue(released.await(2, TimeUnit.SECONDS));
                assertEquals(PluginState.STOPPING, managed.state());
                assertFalse(closed.isDone());
                assertFalse(result.isDone());
                assertEquals(0, plugin.cleaned.get());
                assertThrows(
                        IllegalStateException.class,
                        () -> managed.ownStoppingResource(new Object(), () -> {}));
                drain.complete(true);
                assertTrue(result.get(2, TimeUnit.SECONDS));
                closed.get(2, TimeUnit.SECONDS);
                managed.close();
                assertEquals(1, early.get());
                assertEquals(0, duplicate.get());
                assertEquals(1, plugin.cleaned.get());
            } finally {
                drain.complete(true);
                managed.close();
            }
        }
    }

    @Test
    void unregisterRemovesBothReleaseStagesAndEarlySelfReleasePreventsSecondClose()
            throws Exception {
        var plugin = new TestPlugin();
        var removed = new AtomicInteger();
        var closed = new AtomicInteger();
        try (var lifecycle = Executors.newSingleThreadExecutor()) {
            var managed = start(plugin, lifecycle);
            var removedIdentity = new Object();
            managed.ownResource(removedIdentity, removed::incrementAndGet);
            managed.ownStoppingResource(removedIdentity, removed::incrementAndGet);
            managed.releaseResource(removedIdentity);
            var identity = new Object();
            managed.ownResource(identity, closed::incrementAndGet);
            managed.ownStoppingResource(
                    identity,
                    () -> {
                        closed.incrementAndGet();
                        managed.releaseResource(identity);
                    });
            managed.close();
            managed.close();
            assertEquals(0, removed.get());
            assertEquals(1, closed.get());
            assertEquals(1, plugin.cleaned.get());
        }
    }

    @Test
    void throwingEarlyReleaseFailsAndCleansOwnedWaitBeforeDraining() throws Exception {
        var plugin = new TestPlugin();
        var entered = new CountDownLatch(1);
        var blocked = new CompletableFuture<Boolean>();
        var early = new AtomicInteger();
        var cleanup = new AtomicInteger();
        try (var lifecycle = Executors.newSingleThreadExecutor();
                var calls = Executors.newVirtualThreadPerTaskExecutor()) {
            var managed = start(plugin, lifecycle);
            managed.ownStoppingResource(
                    new Object(),
                    () -> {
                        early.incrementAndGet();
                        throw new IllegalStateException("Cancellation callback failed");
                    });
            managed.ownResource(
                    () -> {
                        cleanup.incrementAndGet();
                        blocked.complete(true);
                    });
            var result =
                    calls.submit(
                            () ->
                                    managed.execute(
                                            () -> {
                                                entered.countDown();
                                                return blocked.join();
                                            }));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                var closed = calls.submit(managed::close);
                closed.get(2, TimeUnit.SECONDS);
                var failure =
                        assertThrows(
                                ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
                var cause = failure.getCause();
                if (cause == null) throw new AssertionError("Missing failure cause");
                assertInstanceOf(PluginFailure.class, cause);
                assertEquals(PluginState.FAILED, managed.state());
                managed.close();
                assertEquals(1, early.get());
                assertEquals(1, cleanup.get());
                assertEquals(1, plugin.cleaned.get());
            } finally {
                blocked.complete(true);
                managed.close();
            }
        }
    }

    private static @NonNull ManagedPlugin start(
            @NonNull TestPlugin plugin, @NonNull ExecutorService lifecycle) throws PluginFailure {
        var managed = new ManagedPlugin(plugin, lifecycle);
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
        return managed;
    }

    private static final class TestPlugin extends AbstractVetoPlugin {
        private final AtomicInteger cleaned = new AtomicInteger();

        public @NonNull PluginIdentity identity() {
            return new PluginIdentity("test.resources", "1.0.0");
        }

        protected @NonNull PluginContributions onInitialize(
                @NonNull PluginContext context, JsonValue.@NonNull ObjectValue configuration) {
            return new PluginContributions(List.of());
        }

        protected void onStart() {}

        protected void onClose() {
            cleaned.incrementAndGet();
        }
    }
}
