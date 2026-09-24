package top.focess.veto.plugin.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.focess.veto.api.plugin.AbstractVetoPlugin;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginContributions;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.PluginFailure;

@Timeout(10)
class ManagedPluginStoppingTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stopSignalRunsOnceBeforeDrainAndFailureReleasesOwnedResources(boolean throwsOnStop)
            throws Exception {
        var stopping = new AtomicInteger();
        var cleaned = new AtomicInteger();
        var blocked = new CompletableFuture<Boolean>();
        var plugin =
                new AbstractVetoPlugin() {
                    public @NonNull PluginIdentity identity() {
                        return new PluginIdentity("test.stop", "1.0.0");
                    }

                    protected @NonNull PluginContributions onInitialize(
                            @NonNull PluginContext context, JsonValue.@NonNull ObjectValue config) {
                        return new PluginContributions(List.of());
                    }

                    protected void onStart() {}

                    protected void onStopping() {
                        stopping.incrementAndGet();
                        if (throwsOnStop) throw new IllegalStateException("Stop failed");
                        blocked.complete(true);
                    }

                    protected void onClose() {
                        cleaned.incrementAndGet();
                    }
                };
        try (var lifecycle = Executors.newSingleThreadExecutor();
                var calls = Executors.newVirtualThreadPerTaskExecutor()) {
            var managed = new ManagedPlugin(plugin, lifecycle);
            managed.initialize(
                    new PluginContext(plugin.identity()), new JsonValue.ObjectValue(Map.of()));
            managed.start();
            managed.ownResource(() -> blocked.complete(true));
            var entered = new CountDownLatch(1);
            var result =
                    calls.submit(
                            () ->
                                    managed.execute(
                                            () -> {
                                                entered.countDown();
                                                return blocked.join();
                                            }));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var closed = calls.submit(managed::close);
            closed.get(2, TimeUnit.SECONDS);
            if (throwsOnStop) {
                var failure =
                        assertThrows(
                                ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
                var cause = failure.getCause();
                if (cause == null) throw new AssertionError("Missing plugin failure");
                assertInstanceOf(PluginFailure.class, cause);
                assertEquals(PluginState.FAILED, managed.state());
            } else assertTrue(result.get(2, TimeUnit.SECONDS));
            managed.close();
            assertEquals(1, stopping.get());
            assertEquals(1, cleaned.get());
        }
    }

    @Test
    void ordinaryAdmittedOperationIsNotInterruptedOrCleanedBeforeItCompletes() throws Exception {
        var stopSeen = new CountDownLatch(1);
        var cleaned = new AtomicInteger();
        var release = new CompletableFuture<Boolean>();
        var plugin =
                new AbstractVetoPlugin() {
                    public @NonNull PluginIdentity identity() {
                        return new PluginIdentity("test.drain", "1.0.0");
                    }

                    protected @NonNull PluginContributions onInitialize(
                            @NonNull PluginContext context, JsonValue.@NonNull ObjectValue config) {
                        return new PluginContributions(List.of());
                    }

                    protected void onStart() {}

                    protected void onStopping() {
                        stopSeen.countDown();
                    }

                    protected void onClose() {
                        cleaned.incrementAndGet();
                    }
                };
        try (var lifecycle = Executors.newSingleThreadExecutor();
                var calls = Executors.newVirtualThreadPerTaskExecutor()) {
            var managed = new ManagedPlugin(plugin, lifecycle);
            managed.initialize(
                    new PluginContext(plugin.identity()), new JsonValue.ObjectValue(Map.of()));
            managed.start();
            var entered = new CountDownLatch(1);
            var result =
                    calls.submit(
                            () ->
                                    managed.execute(
                                            () -> {
                                                entered.countDown();
                                                return release.join();
                                            }));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                var closed = calls.submit(managed::close);
                assertTrue(stopSeen.await(2, TimeUnit.SECONDS));
                assertFalse(result.isDone());
                assertFalse(closed.isDone());
                assertEquals(0, cleaned.get());
                assertThrows(PluginFailure.class, () -> managed.execute(() -> true));
                release.complete(true);
                assertTrue(result.get(2, TimeUnit.SECONDS));
                closed.get(2, TimeUnit.SECONDS);
                assertEquals(1, cleaned.get());
            } finally {
                release.complete(true);
                managed.close();
            }
        }
    }
}
