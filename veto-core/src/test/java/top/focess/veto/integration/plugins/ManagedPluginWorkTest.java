package top.focess.veto.integration.plugins;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static top.focess.veto.util.Nullness.requireNonNull;

import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.agent.workflow.PluginWork;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.builtin.BuiltinPlugin;
import top.focess.veto.plugin.runtime.*;

class ManagedPluginWorkTest {
    @Test
    void unloadingTheSubmittingPluginRejectsDeferredSteps() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            var plugin = new BuiltinPlugin();
            var managed = new ManagedPlugin(plugin, executor);
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
            var delegate = mock(requireNonNull(PluginWork.class));
            var runtime = mock(requireNonNull(PluginWork.Runtime.class));
            var continuation = new ManagedPluginWork(managed, delegate);
            continuation.run(runtime);
            verify(delegate).run(runtime);
            managed.close();
            assertThrows(IllegalStateException.class, () -> continuation.run(runtime));
            verifyNoMoreInteractions(delegate);
        }
    }
}
