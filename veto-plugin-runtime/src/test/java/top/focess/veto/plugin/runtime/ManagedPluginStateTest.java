package top.focess.veto.plugin.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.VetoPlugin;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.PluginFailure;

class ManagedPluginStateTest {
    @Test
    void closeDrainsActiveCallsAndRejectsNewAdmission() throws Exception {
        try (var control = Executors.newSingleThreadExecutor();
                var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var observer = new Observer(false);
            var managed = new ManagedPlugin(observer, control);
            observer.lifecycle = managed;
            managed.construct(
                    new PluginContext(
                            observer.identity(),
                            () -> {},
                            () -> {
                                throw new IllegalStateException(
                                        "Plugin context is not bound to a lifecycle userId");
                            },
                            Map.of(),
                            Map.of()),
                    new JsonValue.ObjectValue(Map.of()));
            managed.start();
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var call =
                    callers.submit(
                            () ->
                                    managed.execute(
                                            () -> {
                                                entered.countDown();
                                                try {
                                                    if (!release.await(5, TimeUnit.SECONDS))
                                                        throw new AssertionError(
                                                                "Release timed out");
                                                } catch (InterruptedException e) {
                                                    throw new AssertionError(e);
                                                }
                                                return "done";
                                            }));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var close = callers.submit(managed::close);
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (managed.state() != PluginState.STOPPING && System.nanoTime() < deadline)
                    Thread.sleep(1);
                assertEquals(PluginState.STOPPING, managed.state());
                assertFalse(close.isDone());
                assertThrows(PluginFailure.class, () -> managed.execute(() -> "late"));
            } finally {
                release.countDown();
            }
            assertEquals("done", call.get(2, TimeUnit.SECONDS));
            close.get(2, TimeUnit.SECONDS);
            managed.close();
            assertEquals(
                    1, observer.callbacks.stream().filter(s -> s == PluginState.STOPPING).count());
        }
    }

    @Test
    void nestedCallsReuseAdmissionButCannotCloseTheirOwnPlugin() throws Exception {
        try (var control = Executors.newSingleThreadExecutor()) {
            var observer = new Observer(false);
            try (var managed = new ManagedPlugin(observer, control)) {
                observer.lifecycle = managed;
                managed.construct(
                        new PluginContext(
                                observer.identity(),
                                () -> {},
                                () -> {
                                    throw new IllegalStateException(
                                            "Plugin context is not bound to a lifecycle userId");
                                },
                                Map.of(),
                                Map.of()),
                        new JsonValue.ObjectValue(Map.of()));
                managed.start();
                assertEquals(
                        "nested",
                        managed.execute(
                                () -> {
                                    assertThrows(IllegalStateException.class, managed::close);
                                    return managed.execute(() -> "nested");
                                }));
            }
        }
    }

    @Test
    void crossPluginNestingRestoresInvocationGuardAndReleasesSlotsAfterFailure() throws Exception {
        try (var control = Executors.newSingleThreadExecutor()) {
            var firstPlugin = new Observer(false);
            var secondPlugin = new Observer(false);
            var first = new ManagedPlugin(firstPlugin, control);
            var second = new ManagedPlugin(secondPlugin, control);
            try {
                firstPlugin.lifecycle = first;
                secondPlugin.lifecycle = second;
                for (var managed : List.of(first, second)) {
                    managed.construct(
                            new PluginContext(
                                    managed.identity(),
                                    () -> {},
                                    managed::state,
                                    Map.of(),
                                    Map.of()),
                            new JsonValue.ObjectValue(Map.of()));
                    managed.start();
                }
                assertThrows(
                        PluginFailure.class,
                        () ->
                                first.execute(
                                        () -> {
                                            assertEquals(
                                                    "nested",
                                                    second.execute(
                                                            () -> {
                                                                assertThrows(
                                                                        IllegalStateException.class,
                                                                        first::close);
                                                                assertThrows(
                                                                        IllegalStateException.class,
                                                                        second::close);
                                                                return first.execute(
                                                                        () -> "nested");
                                                            }));
                                            // Returning from the second activation must retain the
                                            // first's guard.
                                            assertThrows(
                                                    IllegalStateException.class, second::close);
                                            throw new PluginFailure(
                                                    PluginFailure.Code.INTERNAL_FAILURE);
                                        }));
                // The thrown operation must release both slots and clear the caller-thread guard.
                assertEquals("ready", first.execute(() -> "ready"));
                second.close();
                first.close();
                assertEquals(PluginState.CLOSED, first.state());
                assertEquals(PluginState.CLOSED, second.state());
            } finally {
                try {
                    second.close();
                } finally {
                    first.close();
                }
            }
        }
    }

    private static final class Observer extends VetoPlugin {
        private ManagedPlugin lifecycle;
        private final @NonNull List<PluginState> callbacks = new ArrayList<>();
        private final @NonNull List<String> releases = new ArrayList<>();
        private final boolean failStart;

        Observer(boolean failStart) {
            this.failStart = failStart;
        }

        public @NonNull PluginIdentity identity() {
            return new PluginIdentity("test.observer", "1.0.0");
        }

        @NonNull ManagedPlugin lifecycle() {
            var current = lifecycle;
            if (current == null) throw new IllegalStateException("Not bound");
            return current;
        }

        public void start() {
            callbacks.add(lifecycle().state());
            if (failStart) throw new IllegalStateException("Start failed");
        }

        public void close() {
            callbacks.add(lifecycle().state());
        }
    }

    @Test
    void lifecycleObservesCallbacksAndSubsequentTransitions() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            var observer = new Observer(false);
            var managed = new ManagedPlugin(observer, executor);
            observer.lifecycle = managed;
            try {
                assertEquals(PluginState.NEW, managed.state());
                managed.construct(
                        new PluginContext(
                                observer.identity(),
                                () -> {},
                                () -> {
                                    throw new IllegalStateException(
                                            "Plugin context is not bound to a lifecycle userId");
                                },
                                Map.of(),
                                Map.of()),
                        new JsonValue.ObjectValue(Map.of()));
                assertEquals(PluginState.INITIALIZED, managed.state());
                managed.start();
                assertEquals(PluginState.ACTIVE, managed.state());
                assertEquals(managed.state(), managed.execute(managed::state));
                managed.close();
                assertEquals(PluginState.CLOSED, managed.state());
                assertEquals(
                        List.of(PluginState.STARTING, PluginState.STOPPING), observer.callbacks);
            } finally {
                managed.close();
            }
        }
    }

    @Test
    void failureCleanupSeesTheOwnersFailedState() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            var observer = new Observer(true);
            var managed = new ManagedPlugin(observer, executor);
            observer.lifecycle = managed;
            try {
                managed.construct(
                        new PluginContext(
                                observer.identity(),
                                () -> {},
                                () -> {
                                    throw new IllegalStateException(
                                            "Plugin context is not bound to a lifecycle userId");
                                },
                                Map.of(),
                                Map.of()),
                        new JsonValue.ObjectValue(Map.of()));
                managed.registerResource(() -> observer.releases.add("first"));
                managed.registerResource(() -> observer.releases.add("second"));
                assertThrows(PluginFailure.class, managed::start);
                assertEquals(PluginState.FAILED, managed.state());
                assertEquals(List.of(PluginState.STARTING, PluginState.FAILED), observer.callbacks);
                assertEquals(List.of("second", "first"), observer.releases);
            } finally {
                managed.close();
            }
        }
    }
}
