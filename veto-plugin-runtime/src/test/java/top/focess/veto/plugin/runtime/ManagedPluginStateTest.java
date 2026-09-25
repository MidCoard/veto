package top.focess.veto.plugin.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.plugin.*;
import top.focess.veto.api.plugin.AbstractVetoPlugin;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginContributions;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.PluginFailure;

class ManagedPluginStateTest {
    @Test
    void closeDrainsActiveCallsAndRejectsNewAdmission() throws Exception {
        try (var control = Executors.newSingleThreadExecutor();
                var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var observer = new Observer(false);
            var managed = new ManagedPlugin(observer, control);
            managed.initialize(
                    new PluginContext(
                            observer.identity(),
                            () -> {},
                            () -> {
                                throw new IllegalStateException(
                                        "Plugin context is not bound to a lifecycle owner");
                            },
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
                managed.initialize(
                        new PluginContext(
                                observer.identity(),
                                () -> {},
                                () -> {
                                    throw new IllegalStateException(
                                            "Plugin context is not bound to a lifecycle owner");
                                },
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

    private static final class Observer extends AbstractVetoPlugin {
        private @Nullable PluginContext context;
        private final @NonNull List<PluginState> callbacks = new ArrayList<>();
        private final boolean failStart;

        Observer(boolean failStart) {
            this.failStart = failStart;
        }

        public @NonNull PluginIdentity identity() {
            return new PluginIdentity("test.observer", "1.0.0");
        }

        @NonNull PluginContext context() {
            var current = context;
            if (current == null) throw new IllegalStateException("Not initialized");
            return current;
        }

        protected @NonNull PluginContributions onInitialize(
                @NonNull PluginContext context, JsonValue.@NonNull ObjectValue configuration) {
            this.context = context;
            callbacks.add(context.state());
            return new PluginContributions(List.of());
        }

        protected void onStart() {
            callbacks.add(context().state());
            if (failStart) throw new IllegalStateException("Start failed");
        }

        protected void onClose() {
            callbacks.add(context().state());
        }
    }

    @Test
    void sameContextObservesCallbacksAndSubsequentTransitions() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            var observer = new Observer(false);
            var managed = new ManagedPlugin(observer, executor);
            try {
                assertEquals(PluginState.NEW, managed.state());
                managed.initialize(
                        new PluginContext(
                                observer.identity(),
                                () -> {},
                                () -> {
                                    throw new IllegalStateException(
                                            "Plugin context is not bound to a lifecycle owner");
                                },
                                Map.of()),
                        new JsonValue.ObjectValue(Map.of()));
                var context = observer.context();
                assertEquals(PluginState.INITIALIZED, context.state());
                managed.start();
                assertEquals(PluginState.ACTIVE, context.state());
                assertEquals(managed.state(), managed.execute(context::state));
                managed.close();
                assertEquals(PluginState.CLOSED, context.state());
                assertEquals(
                        List.of(
                                PluginState.INITIALIZING,
                                PluginState.STARTING,
                                PluginState.STOPPING),
                        observer.callbacks);
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
            try {
                managed.initialize(
                        new PluginContext(
                                observer.identity(),
                                () -> {},
                                () -> {
                                    throw new IllegalStateException(
                                            "Plugin context is not bound to a lifecycle owner");
                                },
                                Map.of()),
                        new JsonValue.ObjectValue(Map.of()));
                assertThrows(PluginFailure.class, managed::start);
                assertEquals(PluginState.FAILED, observer.context().state());
                assertEquals(managed.state(), observer.context().state());
                assertEquals(
                        List.of(PluginState.INITIALIZING, PluginState.STARTING, PluginState.FAILED),
                        observer.callbacks);
            } finally {
                managed.close();
            }
        }
    }
}
