package top.focess.veto.integration.plugins;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static top.focess.veto.util.Nullness.requireNonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.agent.workflow.ModelFlow;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contribution.Contribution;
import top.focess.veto.api.plugin.contribution.ContributionPoint;
import top.focess.veto.builtin.BuiltinPlugin;
import top.focess.veto.plugin.runtime.*;

class ManagedPluginFlowTest {
    @Test
    void builtinRejectsConstructionWithoutHost() {
        var context =
                new PluginContext(
                        new PluginIdentity("top.focess.builtin", "1.0.100"),
                        () -> {},
                        () -> {
                            throw new IllegalStateException("No lifecycle userId");
                        },
                        Map.of(),
                        Map.of());
        assertThrows(
                IllegalStateException.class,
                () -> new BuiltinPlugin(context, new JsonValue.ObjectValue(Map.of())));
    }

    @Test
    void unloadingTheSubmittingPluginRejectsDeferredSteps() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            Map<@NonNull ContributionPoint<?>, @NonNull Consumer<@NonNull Contribution<?>>>
                    handlers = new HashMap<>();
            for (var point : StandardContributionPoints.ALL)
                handlers.put(point, contribution -> {});
            var context =
                    new PluginContext(
                            new PluginIdentity("top.focess.builtin", "1.0.100"),
                            () -> {},
                            () -> {
                                throw new IllegalStateException(
                                        "Plugin context is not bound to a lifecycle userId");
                            },
                            Map.of(PluginHost.class, mock(requireNonNull(PluginHost.class))),
                            handlers);
            var configuration = new JsonValue.ObjectValue(Map.of());
            var managed = new ManagedPlugin(new BuiltinPlugin(context, configuration), executor);
            managed.construct(context, configuration);
            managed.start();
            var delegate = mock(requireNonNull(ModelFlow.class));
            var runtime = mock(requireNonNull(ModelFlow.Runtime.class));
            var continuation = new ManagedPluginFlow(managed, delegate);
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
