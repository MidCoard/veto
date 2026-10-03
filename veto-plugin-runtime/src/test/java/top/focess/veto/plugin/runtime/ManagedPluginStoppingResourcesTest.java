package top.focess.veto.plugin.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import top.focess.veto.api.agent.workflow.PluginAwait;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.VetoPlugin;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.PluginFailure;

@Timeout(10)
class ManagedPluginStoppingResourcesTest {
    @Test
    void stoppingFailsOwnedAwaitBeforeDrainingItsAdmittedHandler() throws Exception {
        var plugin = new TestPlugin();
        var entered = new CountDownLatch(1);
        var source = new CompletableFuture<Boolean>();
        try (var lifecycle = Executors.newSingleThreadExecutor();
                var calls = Executors.newVirtualThreadPerTaskExecutor()) {
            var managed = start(plugin, lifecycle);
            var owned = managed.ownAwait(new PluginAwait("waiting", source));
            var result =
                    calls.submit(
                            () ->
                                    managed.execute(
                                            () -> {
                                                entered.countDown();
                                                return owned.ready().join();
                                            }));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                var closed = calls.submit(managed::close);
                closed.get(2, TimeUnit.SECONDS);
                var failure =
                        assertThrows(
                                ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
                var completion = assertInstanceOf(CompletionException.class, failure.getCause());
                assertInstanceOf(IllegalStateException.class, completion.getCause());
                assertTrue(source.isCancelled());
                assertEquals(PluginState.CLOSED, managed.state());
                assertEquals(1, plugin.cleaned.get());
            } finally {
                source.complete(true);
                managed.close();
            }
        }
    }

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

    @Test
    void resourceAssertionDoesNotSkipRemainingCleanupOrLeaveCloseWaiting() throws Exception {
        var plugin = new TestPlugin();
        var remaining = new AtomicInteger();
        var hostRelease = new AtomicInteger();
        try (var lifecycle = Executors.newSingleThreadExecutor()) {
            var managed = start(plugin, lifecycle);
            managed.registerResource(remaining::incrementAndGet);
            managed.registerResource(
                    () -> {
                        throw new AssertionError("synthetic plugin error");
                    });
            managed.ownResource(hostRelease::incrementAndGet);
            managed.close();
            assertEquals(PluginState.FAILED, managed.state());
            assertEquals(1, remaining.get());
            assertEquals(1, hostRelease.get());
            assertEquals(1, plugin.cleaned.get());
            managed.close();
            assertEquals(1, remaining.get());
        }
    }

    @Test
    void fatalResourceErrorPropagatesAfterOtherCleanupAndCompletesTheCloseSignal()
            throws Exception {
        var plugin = new TestPlugin();
        var remaining = new AtomicInteger();
        var hostRelease = new AtomicInteger();
        try (var lifecycle = Executors.newSingleThreadExecutor()) {
            var managed = start(plugin, lifecycle);
            managed.registerResource(remaining::incrementAndGet);
            managed.registerResource(
                    () -> {
                        throw new InternalError("synthetic fatal error");
                    });
            managed.ownResource(hostRelease::incrementAndGet);
            var failure = assertThrows(CompletionException.class, managed::close);
            assertInstanceOf(InternalError.class, failure.getCause());
            assertEquals(1, remaining.get());
            assertEquals(1, hostRelease.get());
            assertEquals(1, plugin.cleaned.get());
            managed.close();
        }
    }

    @Test
    // Verify the legacy fatal signal is preserved while supported by the JDK.
    @SuppressWarnings("removal")
    void fatalStoppingErrorSurvivesLaterFailuresAndReleasesEveryRemainingResource()
            throws Exception {
        for (boolean hostStopping : List.of(true, false)) {
            var plugin = new TestPlugin();
            Error original = hostStopping ? new InternalError("host stopping") : new ThreadDeath();
            plugin.stoppingError = hostStopping ? new ThreadDeath() : original;
            var earlyRelease = new AtomicInteger();
            var contributedRelease = new AtomicInteger();
            var hostRelease = new AtomicInteger();
            try (var lifecycle = Executors.newSingleThreadExecutor()) {
                var managed = start(plugin, lifecycle);
                managed.ownStoppingResource(new Object(), earlyRelease::incrementAndGet);
                if (hostStopping) {
                    managed.ownStoppingResource(
                            new Object(),
                            () -> {
                                throw original;
                            });
                }
                managed.registerResource(contributedRelease::incrementAndGet);
                managed.registerResource(
                        () -> {
                            throw new InternalError("later cleanup");
                        });
                managed.ownResource(hostRelease::incrementAndGet);

                var failure = assertThrows(CompletionException.class, managed::close);
                var cause = failure.getCause();
                if (cause == null) throw new AssertionError("Missing fatal stopping cause");
                assertSame(original, cause);
                assertEquals(PluginState.FAILED, managed.state());
                assertEquals(1, earlyRelease.get());
                assertEquals(1, plugin.stopped.get());
                assertEquals(1, contributedRelease.get());
                assertEquals(1, hostRelease.get());
                assertEquals(1, plugin.cleaned.get());
                managed.close();
                assertEquals(1, plugin.stopped.get());
                assertEquals(1, plugin.cleaned.get());
            }
        }
    }

    private static @NonNull ManagedPlugin start(
            @NonNull TestPlugin plugin, @NonNull ExecutorService lifecycle) throws PluginFailure {
        var managed = new ManagedPlugin(plugin, lifecycle);
        managed.construct(
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
        return managed;
    }

    private static final class TestPlugin extends VetoPlugin {

        private final @NonNull AtomicInteger cleaned = new AtomicInteger();
        private final @NonNull AtomicInteger stopped = new AtomicInteger();
        private Error stoppingError;

        public @NonNull PluginIdentity identity() {
            return new PluginIdentity("test.resources", "1.0.0");
        }

        public void start() {}

        @Override
        public void stopping() {
            stopped.incrementAndGet();
            var failure = stoppingError;
            if (failure != null) throw failure;
        }

        public void close() {
            cleaned.incrementAndGet();
        }
    }
}
