package top.focess.veto.plugin.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import top.focess.veto.extension.contract.ExtensionFailure;
import top.focess.veto.extension.contract.JsonValue;
import top.focess.veto.plugin.api.*;

class ManagedPluginStateTest {
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
                        new PluginContext(observer.identity()),
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
                        new PluginContext(observer.identity()),
                        new JsonValue.ObjectValue(Map.of()));
                assertThrows(ExtensionFailure.class, managed::start);
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
