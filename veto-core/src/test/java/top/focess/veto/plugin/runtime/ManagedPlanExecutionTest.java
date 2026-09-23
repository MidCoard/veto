package top.focess.veto.plugin.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static top.focess.veto.util.Nullness.requireNonNull;

import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.agent.workflow.PlanExecution;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.builtin.BuiltinPlugin;

class ManagedPlanExecutionTest {
    @Test
    void unloadingTheSubmittingPluginRejectsDeferredSteps() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            var plugin = new BuiltinPlugin();
            var managed = new ManagedPlugin(plugin, executor);
            managed.initialize(
                    new PluginContext(plugin.identity()), new JsonValue.ObjectValue(Map.of()));
            managed.start();
            var delegate = mock(requireNonNull(PlanExecution.class));
            var runtime = mock(requireNonNull(PlanExecution.Runtime.class));
            var continuation = new ManagedPlanExecution(managed, delegate);
            continuation.step(runtime);
            verify(delegate).step(runtime);
            managed.close();
            assertThrows(IllegalStateException.class, () -> continuation.step(runtime));
            verifyNoMoreInteractions(delegate);
        }
    }
}
