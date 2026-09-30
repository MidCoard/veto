package top.focess.veto.plugin.runtime;

import top.focess.veto.api.plugin.PluginScope;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.plugin.*;
import top.focess.veto.api.plugin.contract.*;
import top.focess.veto.api.plugin.contribution.*;
import top.focess.veto.api.plugin.service.*;
import top.focess.veto.api.plugin.storage.PluginStorage;

class PluginServiceRegistryTest {
    private static @NonNull PluginService service(
            @NonNull String name, int version, @NonNull ServiceHandler handler) {
        return service(
                name,
                version,
                PluginScope.APPLICATION,
                (caller, request) -> handler.invoke(request));
    }

    private static @NonNull PluginService service(
            @NonNull String name,
            int version,
            @NonNull PluginScope scope,
            @NonNull ScopedServiceHandler handler) {
        return new PluginService(name, version, scope) {
            @Override
            public @NonNull JsonValue invoke(
                    @NonNull ServiceCallContext caller, @NonNull JsonValue request)
                    throws ServiceException {
                try {
                    return handler.invoke(caller, request);
                } catch (ServiceException failure) {
                    throw failure;
                } catch (Exception failure) {
                    throw new ServiceException(ServiceException.Code.FAILED);
                }
            }
        };
    }

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
            assertEquals(5, services.available().size());
            assertThrows(
                    ClassNotFoundException.class,
                    () -> Class.forName("top.focess.veto.builtin.BuiltinPlugin"));
        }
    }

    @Test
    void userServiceRequiresAValidatedCallerScope() throws Exception {
        try (var pair = new Pair()) {
            var handle = pair.consumer.context.services().find("demo:user", 1).orElseThrow();
            assertEquals(PluginScope.USER, handle.descriptor().scope());
            assertEquals(
                    ServiceException.Code.INVALID_REQUEST,
                    assertThrows(
                                    ServiceException.class,
                                    () -> handle.invoke(JsonValue.NullValue.INSTANCE))
                            .code());
            assertEquals(
                    ServiceException.Code.UNAVAILABLE,
                    assertThrows(
                                    ServiceException.class,
                                    () ->
                                            handle.invoke(
                                                    new PluginStorage.UserScope("forged", "owner"),
                                                    JsonValue.NullValue.INSTANCE))
                            .code());
            assertEquals(
                    new JsonValue.StringValue("owner"),
                    handle.invoke(
                            new PluginStorage.UserScope("valid", "owner"),
                            JsonValue.NullValue.INSTANCE));
        }
    }

    @Test
    void sessionServiceRejectsUserAndExpiredSessionScopes() throws Exception {
        try (var pair = new Pair()) {
            var handle = pair.consumer.context.services().find("demo:session", 1).orElseThrow();
            assertEquals(PluginScope.SESSION, handle.descriptor().scope());
            assertEquals(
                    ServiceException.Code.UNAVAILABLE,
                    assertThrows(
                                    ServiceException.class,
                                    () ->
                                            handle.invoke(
                                                    new PluginStorage.UserScope("valid", "owner"),
                                                    JsonValue.NullValue.INSTANCE))
                            .code());
            assertEquals(
                    ServiceException.Code.UNAVAILABLE,
                    assertThrows(
                                    ServiceException.class,
                                    () ->
                                            handle.invoke(
                                                    new PluginStorage.SessionScope(
                                                            "expired", "owner", "session"),
                                                    JsonValue.NullValue.INSTANCE))
                            .code());
            assertEquals(
                    new JsonValue.StringValue("session"),
                    handle.invoke(
                            new PluginStorage.SessionScope("valid", "owner", "session"),
                            JsonValue.NullValue.INSTANCE));
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

    @Test
    void revokingProviderInvalidatesRetainedHandleAndDiscovery() throws Exception {
        try (var pair = new Pair()) {
            var services = pair.consumer.context.services();
            var handle = services.find("demo:echo", 1).orElseThrow();
            pair.registry.revoke("demo.provider");
            assertTrue(services.find("demo:echo", 1).isEmpty());
            assertEquals(1, services.available().size());
            assertEquals(
                    ServiceException.Code.UNAVAILABLE,
                    assertThrows(
                                    ServiceException.class,
                                    () -> handle.invoke(JsonValue.NullValue.INSTANCE))
                            .code());
        }
    }

    private static final class TestPlugin extends VetoPlugin {
        final @NonNull String id;
        @NonNull PluginContext context =
                new PluginContext(
                        new PluginIdentity("unbound", "1.0.0"),
                        () -> {},
                        () -> {
                            throw new IllegalStateException(
                                    "Plugin context is not bound to a lifecycle owner");
                        },
                        Map.of(),
                        Map.of());

        TestPlugin(@NonNull String id) {
            this.id = id;
        }

        public @NonNull PluginIdentity identity() {
            return new PluginIdentity(id, "1.0.0");
        }

        public @NonNull PluginContributions initialize(
                @NonNull PluginContext context, JsonValue.@NonNull ObjectValue configuration) {
            this.context = context;
            return contributions();
        }

        @Override
        public @NonNull PluginContributions contributions() {
            if (id.equals("demo.provider"))
                return new PluginContributions(
                        List.of(
                                Contribution.of(
                                        StandardContributionPoints.SERVICES,
                                        "echo",
                                        service("demo:echo", 1, request -> request)),
                                Contribution.of(
                                        StandardContributionPoints.SERVICES,
                                        "failure",
                                        service(
                                                "demo:failure",
                                                1,
                                                request -> {
                                                    throw new IllegalStateException(
                                                            "private-provider-secret");
                                                })),
                                Contribution.of(
                                        StandardContributionPoints.SERVICES,
                                        "user",
                                        service(
                                                "demo:user",
                                                1,
                                                PluginScope.USER,
                                                (call, request) -> {
                                                    String owner = call.userId();
                                                    if (owner == null)
                                                        throw new AssertionError("Missing user");
                                                    return new JsonValue.StringValue(owner);
                                                })),
                                Contribution.of(
                                        StandardContributionPoints.SERVICES,
                                        "session",
                                        service(
                                                "demo:session",
                                                1,
                                                PluginScope.SESSION,
                                                (call, request) -> {
                                                    String session = call.sessionId();
                                                    if (session == null)
                                                        throw new AssertionError("Missing session");
                                                    return new JsonValue.StringValue(session);
                                                }))));
            return new PluginContributions(
                    List.of(
                            Contribution.of(
                                    StandardContributionPoints.SERVICES,
                                    "relay",
                                    service(
                                            "demo:relay",
                                            1,
                                            request ->
                                                    context.services()
                                                            .find("demo:echo", 1)
                                                            .orElseThrow()
                                                            .invoke(request)))));
        }

        public void start() {}

        public void close() {}
    }

    private static final class Pair implements AutoCloseable {
        final @NonNull ExecutorService executor = Executors.newSingleThreadExecutor();
        final @NonNull AtomicBoolean allowed = new AtomicBoolean(true);
        final @NonNull PluginServiceRegistry registry =
                new PluginServiceRegistry(
                        (caller, provider) -> allowed.get(),
                        (caller, provider, required, scope) -> {
                            if (!scope.token().equals("valid"))
                                throw new ServiceException(ServiceException.Code.UNAVAILABLE);
                            if (required == PluginScope.USER
                                    && scope instanceof PluginStorage.UserScope user)
                                return new ServiceCallContext(
                                        caller, required, new Scope.UserScope(user.userId()), user);
                            if (required == PluginScope.SESSION
                                    && scope instanceof PluginStorage.SessionScope session)
                                return new ServiceCallContext(
                                        caller,
                                        required,
                                        new Scope.SessionScope(
                                                session.userId(), session.sessionId()),
                                        session);
                            throw new ServiceException(ServiceException.Code.UNAVAILABLE);
                        });
        final @NonNull TestPlugin consumer = new TestPlugin("demo.consumer");
        final @NonNull PluginLifecycle consumerRuntime = new PluginLifecycle(consumer, executor);
        final @NonNull PluginLifecycle providerRuntime =
                new PluginLifecycle(new TestPlugin("demo.provider"), executor);

        Pair() throws Exception {
            var builder =
                    new ContributionCatalog.Builder()
                            .define(StandardContributionPoints.SERVICES, ignored -> {});
            for (var runtime : List.of(consumerRuntime, providerRuntime)) {
                var contributions =
                        runtime.initialize(
                                new PluginContext(
                                        runtime.identity(),
                                        () -> {},
                                        () -> {
                                            throw new IllegalStateException(
                                                    "Plugin context is not bound to a lifecycle"
                                                            + " owner");
                                        },
                                        Map.of(PluginServices.class, registry.forPlugin(runtime)),
                                        Map.of()),
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
