package top.focess.veto.integration.plugins;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import top.focess.veto.api.credentials.VaultAccess;
import top.focess.veto.api.llm.LocalModelCompletion;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.VetoPlugin;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.plugin.runtime.ManagedPlugin;
import top.focess.veto.vault.KeysteadVault;
import top.focess.veto.veto.LlamaCppBridge;

class HostResourceConfigurationTest {
    @Test
    void grantsOnlyPresentHostResourcesWithoutAnyFeaturePluginClass() {
        new ApplicationContextRunner()
                .withUserConfiguration(HostResourceConfiguration.class)
                .run(
                        context ->
                                assertTrue(
                                        context.getBean(PluginHostServices.class)
                                                .services()
                                                .isEmpty()));
        new ApplicationContextRunner()
                .withUserConfiguration(HostResourceConfiguration.class)
                .withBean(KeysteadVault.class, () -> mock(KeysteadVault.class))
                .withBean(LlamaCppBridge.class, () -> mock(LlamaCppBridge.class))
                .run(
                        context -> {
                            var services = context.getBean(PluginHostServices.class).services();
                            assertTrue(services.containsKey(VaultAccess.class));
                            assertTrue(services.containsKey(PluginLocalModelFactory.class));
                        });
    }

    @Test
    void localCompletionUsesLiteralGrammarAndRevokesAfterPluginClose() throws Exception {
        LlamaCppBridge bridge = mock(LlamaCppBridge.class);
        when(bridge.isAvailable()).thenReturn(true);
        when(bridge.inferWithGrammar("compiled MDC", "array grammar"))
                .thenReturn(CompletableFuture.completedFuture("[\"token\"]"));
        try (var lifecycle = Executors.newSingleThreadExecutor()) {
            var plugin = active(lifecycle);
            try {
                var port = new BoundLocalModelCompletion(plugin, bridge);
                assertTrue(port.isAvailable());
                assertEquals(Optional.of("[\"token\"]"), port.complete(request()));
                verify(bridge).inferWithGrammar("compiled MDC", "array grammar");
                plugin.close();
                assertFalse(port.isAvailable());
                assertTrue(port.complete(request()).isEmpty());
                verify(bridge, times(1)).inferWithGrammar(anyString(), anyString());
            } finally {
                plugin.close();
            }
        }
    }

    @Test
    void pluginStopCancelsAnAdmittedPendingCompletion() throws Exception {
        LlamaCppBridge bridge = mock(LlamaCppBridge.class);
        when(bridge.isAvailable()).thenReturn(true);
        var pending = new CompletableFuture<String>();
        var started = new CountDownLatch(1);
        when(bridge.inferWithGrammar(anyString(), anyString()))
                .thenAnswer(
                        call -> {
                            started.countDown();
                            return pending;
                        });
        try (var lifecycle = Executors.newSingleThreadExecutor();
                var worker = Executors.newVirtualThreadPerTaskExecutor()) {
            var plugin = active(lifecycle);
            try {
                var port = new BoundLocalModelCompletion(plugin, bridge);
                var result = worker.submit(() -> port.complete(request()));
                assertTrue(started.await(2, TimeUnit.SECONDS));
                plugin.close();
                assertTrue(result.get(2, TimeUnit.SECONDS).isEmpty());
                assertTrue(pending.isCancelled());
            } finally {
                plugin.close();
            }
        }
    }

    @Test
    void deadlineAndInterruptionCancelWithoutInventingModelOutput() throws Exception {
        LlamaCppBridge bridge = mock(LlamaCppBridge.class);
        when(bridge.isAvailable()).thenReturn(true);
        var pending = new CompletableFuture<String>();
        when(bridge.inferWithGrammar(anyString(), anyString())).thenReturn(pending);
        try (var lifecycle = Executors.newSingleThreadExecutor();
                var plugin = active(lifecycle)) {
            var port = new BoundLocalModelCompletion(plugin, bridge);
            assertTrue(port.complete(request()).isEmpty());
            assertTrue(pending.isCancelled());
            Thread.currentThread().interrupt();
            try {
                assertTrue(port.complete(request()).isEmpty());
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
            verify(bridge, times(1)).inferWithGrammar(anyString(), anyString());
        }
    }

    @Test
    void oversizedInputAndUnavailableModelsDoNotInvokeTransport() throws Exception {
        LlamaCppBridge bridge = mock(LlamaCppBridge.class);
        try (var lifecycle = Executors.newSingleThreadExecutor();
                var plugin = active(lifecycle)) {
            var port = new BoundLocalModelCompletion(plugin, bridge);
            assertTrue(
                    port.complete(
                                    new LocalModelCompletion.Request(
                                            "purpose", "x".repeat(65537), "grammar"))
                            .isEmpty());
            assertTrue(
                    port.complete(
                                    new LocalModelCompletion.Request(
                                            "../forged", "prompt", "grammar"))
                            .isEmpty());
            assertFalse(port.isAvailable());
            assertTrue(port.complete(request()).isEmpty());
            verify(bridge, never()).inferWithGrammar(anyString(), anyString());
        }
    }

    private static LocalModelCompletion.@NonNull Request request() {
        return new LocalModelCompletion.Request("classification", "compiled MDC", "array grammar");
    }

    private static @NonNull ManagedPlugin active(@NonNull ExecutorService lifecycle)
            throws Exception {
        var plugin =
                new ManagedPlugin(
                        new VetoPlugin() {

                            public @NonNull PluginIdentity identity() {
                                return new PluginIdentity("example.local-model", "1.0.0");
                            }

                            public void start() {}

                            public void close() {}
                        },
                        lifecycle);
        plugin.construct(
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
        plugin.start();
        return plugin;
    }
}
