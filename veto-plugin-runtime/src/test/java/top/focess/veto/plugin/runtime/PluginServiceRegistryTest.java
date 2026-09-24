package top.focess.veto.plugin.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.plugin.*;
import top.focess.veto.api.plugin.contract.*;
import top.focess.veto.api.plugin.contribution.*;
import top.focess.veto.api.plugin.service.*;

class PluginServiceRegistryTest {
    @Test
    void consumerUsesNamedJsonServiceWithoutProviderTypes() throws Exception {
        try (var pair = new Pair()) {
            var services = pair.consumer.context.services();
            assertTrue(services.find("demo:echo", 2).isEmpty());
            assertTrue(services.find("absent", 1).isEmpty());
            var request =
                    new JsonValue.ObjectValue(Map.of("text", new JsonValue.StringValue("hello")));
            var relay = services.find("demo:relay", 1).orElseThrow();
            assertEquals(request, relay.invoke(request));
            assertEquals(
                    "demo.provider",
                    services.find("demo:echo", 1).orElseThrow().descriptor().providerId());
            assertEquals(3, services.available().size());
            assertThrows(
                    ClassNotFoundException.class,
                    () -> Class.forName("top.focess.veto.builtin.BuiltinPlugin"));
        }
    }

    @Test
    void retainedHandlesRecheckVisibilityAndBothLifecycles() throws Exception {
        try (var pair = new Pair()) {
            var handle = pair.consumer.context.services().find("demo:echo", 1).orElseThrow();
            pair.allowed.set(false);
            assertTrue(pair.consumer.context.services().available().isEmpty());
            assertEquals(
                    ServiceException.Code.UNAVAILABLE,
                    assertThrows(
                                    ServiceException.class,
                                    () -> handle.invoke(JsonValue.NullValue.INSTANCE))
                            .code());
            pair.allowed.set(true);
            pair.providerRuntime.close();
            assertEquals(
                    ServiceException.Code.UNAVAILABLE,
                    assertThrows(
                                    ServiceException.class,
                                    () -> handle.invoke(JsonValue.NullValue.INSTANCE))
                            .code());
        }
        try (var pair = new Pair()) {
            var handle = pair.consumer.context.services().find("demo:echo", 1).orElseThrow();
            pair.consumerRuntime.close();
            assertEquals(
                    ServiceException.Code.UNAVAILABLE,
                    assertThrows(
                                    ServiceException.class,
                                    () -> handle.invoke(JsonValue.NullValue.INSTANCE))
                            .code());
        }
    }

    @Test
    void providerDiagnosticsDoNotCrossServiceBoundary() throws Exception {
        try (var pair = new Pair()) {
            var handle = pair.consumer.context.services().find("demo:failure", 1).orElseThrow();
            var failure =
                    assertThrows(
                            ServiceException.class,
                            () -> handle.invoke(JsonValue.NullValue.INSTANCE));
            assertEquals(ServiceException.Code.FAILED, failure.code());
            assertNull(failure.getCause());
            assertEquals("FAILED", failure.getMessage());
        }
    }

    private static final class TestPlugin extends AbstractVetoPlugin {
        final @NonNull String id;
        @NonNull PluginContext context = new PluginContext(new PluginIdentity("unbound", "1.0.0"));

        TestPlugin(@NonNull String id) {
            this.id = id;
        }

        public @NonNull PluginIdentity identity() {
            return new PluginIdentity(id, "1.0.0");
        }

        protected @NonNull PluginContributions onInitialize(
                @NonNull PluginContext context, JsonValue.@NonNull ObjectValue configuration) {
            this.context = context;
            if (id.equals("demo.provider"))
                return new PluginContributions(
                        List.of(
                                Contribution.of(
                                        StandardContributionPoints.SERVICES,
                                        "echo",
                                        new ServiceRegistration(
                                                "demo:echo", 1, request -> request)),
                                Contribution.of(
                                        StandardContributionPoints.SERVICES,
                                        "failure",
                                        new ServiceRegistration(
                                                "demo:failure",
                                                1,
                                                request -> {
                                                    throw new IllegalStateException(
                                                            "private-provider-secret");
                                                }))));
            return new PluginContributions(
                    List.of(
                            Contribution.of(
                                    StandardContributionPoints.SERVICES,
                                    "relay",
                                    new ServiceRegistration(
                                            "demo:relay",
                                            1,
                                            request ->
                                                    context.services()
                                                            .find("demo:echo", 1)
                                                            .orElseThrow()
                                                            .invoke(request)))));
        }

        protected void onStart() {}

        protected void onClose() {}
    }

    private static final class Pair implements AutoCloseable {
        final @NonNull ExecutorService executor = Executors.newSingleThreadExecutor();
        final @NonNull AtomicBoolean allowed = new AtomicBoolean(true);
        final @NonNull TestPlugin consumer = new TestPlugin("demo.consumer");
        final @NonNull ManagedPlugin consumerRuntime = new ManagedPlugin(consumer, executor);
        final @NonNull ManagedPlugin providerRuntime =
                new ManagedPlugin(new TestPlugin("demo.provider"), executor);

        Pair() throws Exception {
            var registry = new PluginServiceRegistry((caller, provider) -> allowed.get());
            var builder =
                    new ContributionCatalog.Builder()
                            .define(StandardContributionPoints.SERVICES, ignored -> {});
            for (var runtime : List.of(consumerRuntime, providerRuntime)) {
                var contributions =
                        runtime.initialize(
                                new PluginContext(
                                        runtime.identity(),
                                        Map.of(
                                                ToolDocs.nonNullClass(PluginServices.class),
                                                registry.forPlugin(runtime))),
                                new JsonValue.ObjectValue(Map.of()));
                builder.stage(
                        new ContributionSource(
                                runtime.identity().id(), "1.0.0", ContributionSource.Origin.PLUGIN),
                        contributions.entries());
            }
            registry.bind(builder.freeze(), List.of(consumerRuntime, providerRuntime));
            consumerRuntime.start();
            providerRuntime.start();
        }

        public void close() {
            consumerRuntime.close();
            providerRuntime.close();
            executor.shutdown();
        }
    }
}
