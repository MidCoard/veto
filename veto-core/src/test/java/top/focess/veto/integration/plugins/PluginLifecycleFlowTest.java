package top.focess.veto.integration.plugins;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static top.focess.veto.util.Nullness.requireNonNull;

import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.agent.workflow.ModelFlow;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.PluginFailure;
import top.focess.veto.builtin.BuiltinPlugin;
import top.focess.veto.plugin.runtime.*;

class PluginLifecycleFlowTest {
    @Test
    void builtinRejectsInitializationWithoutHost() throws Exception {
        var plugin = new BuiltinPlugin();
        var context =
                new PluginContext(
                        plugin.identity(),
                        () -> {},
                        () -> {
                            throw new IllegalStateException("No lifecycle owner");
                        },
                        Map.of());
        assertThrows(
                PluginFailure.class,
                () -> plugin.initialize(context, new JsonValue.ObjectValue(Map.of())));
        plugin.close();
    }

    @Test
    void unloadingTheSubmittingPluginRejectsDeferredSteps() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            var plugin = new BuiltinPlugin();
            var managed = new PluginLifecycle(plugin, executor);
            managed.initialize(
                    new PluginContext(
                            plugin.identity(),
                            () -> {},
                            () -> {
                                throw new IllegalStateException(
                                        "Plugin context is not bound to a lifecycle owner");
                            },
                            Map.of(PluginHost.class, mock(requireNonNull(PluginHost.class)))),
                    new JsonValue.ObjectValue(Map.of()));
            managed.start();
            var delegate = mock(requireNonNull(ModelFlow.class));
            var runtime = mock(requireNonNull(ModelFlow.Runtime.class));
            var continuation = new PluginLifecycleFlow(managed, delegate);
            var nested = mock(requireNonNull(ModelFlow.class));
            var child = continuation.child(nested);
            continuation.run(runtime);
            child.run(runtime);
            verify(delegate).run(runtime);
            verify(nested).run(runtime);
            managed.close();
            assertThrows(IllegalStateException.class, () -> continuation.run(runtime));
            assertThrows(IllegalStateException.class, () -> child.run(runtime));
            verifyNoMoreInteractions(delegate);
            verifyNoMoreInteractions(nested);
        }
    }
}
