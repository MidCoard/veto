package top.focess.veto.integration.plugins;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.VetoPlugin;
import top.focess.veto.api.plugin.contract.Cancellation;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.ObservationMiddleware;
import top.focess.veto.api.plugin.contract.PluginFailure;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contribution.Contribution;
import top.focess.veto.api.plugin.contribution.ContributionCatalog;
import top.focess.veto.api.plugin.contribution.ContributionSource;
import top.focess.veto.event.EventListenerRegistry;
import top.focess.veto.plugin.runtime.*;

/**
 * The session-less {@code veto:observation-middleware} chain in {@link
 * PluginManager#applyObservationMiddleware}: catalog ordering across contributions, identity when
 * no middleware is registered, and failure propagation. The real secret-protection plugin's
 * middleware is covered by {@link PluginManagerDiscoveryTest}.
 */
class ApplyObservationMiddlewareTest {
    @FunctionalInterface
    private interface Transform {
        @NonNull String apply(@NonNull String text, @NonNull Cancellation cancellation)
                throws PluginFailure;
    }

    private static @NonNull ObservationMiddleware middleware(@NonNull Transform transform) {
        return new ObservationMiddleware() {
            @Override
            public @NonNull String transform(
                    @NonNull String observation, @NonNull Cancellation cancellation)
                    throws PluginFailure {
                return transform.apply(observation, cancellation);
            }
        };
    }

    private ExecutorService executor;

    @AfterEach
    void stopExecutor() {
        if (executor != null) executor.shutdownNow();
    }

    private static final class MiddlewarePlugin extends VetoPlugin {
        private final @NonNull PluginIdentity identity;
        private final @NonNull ObservationMiddleware middleware;

        MiddlewarePlugin(@NonNull String id, @NonNull ObservationMiddleware middleware) {
            this.identity = new PluginIdentity(id, "1.0.0");
            this.middleware = middleware;
        }

        @Override
        public @NonNull PluginIdentity identity() {
            return identity;
        }

        public @NonNull Contribution<ObservationMiddleware> contribution() {
            return Contribution.of(
                    StandardContributionPoints.OBSERVATION, "middleware", middleware);
        }

        @Override
        public void start() {}

        @Override
        public void close() {}
    }

    /** A manager whose coherent publication contains the given stub middleware plugins. */
    private @NonNull PluginManager managerWith(@NonNull MiddlewarePlugin @NonNull ... stubs)
            throws Exception {
        var lifecycle = Executors.newSingleThreadExecutor();
        executor = lifecycle;
        List<PluginLifecycle> managed = new ArrayList<>();
        var builder = new ContributionCatalog.Builder();
        builder.define(StandardContributionPoints.OBSERVATION, ignored -> {});
        builder.define(StandardContributionPoints.LISTENERS, ignored -> {});
        for (var stub : stubs) {
            var plugin = new PluginLifecycle(stub, lifecycle);
            plugin.construct(
                    new PluginContext(
                            stub.identity(),
                            () -> {},
                            () -> {
                                throw new IllegalStateException(
                                        "Plugin context is not bound to a lifecycle owner");
                            },
                            Map.of(),
                            Map.of()),
                    new JsonValue.ObjectValue(Map.of()));
            builder.stage(
                    new ContributionSource(
                            stub.identity().id(),
                            stub.identity().version(),
                            ContributionSource.Origin.PLUGIN),
                    List.of(stub.contribution()));
            managed.add(plugin);
        }
        var catalog = builder.freeze();
        for (var plugin : managed) plugin.start();
        var manager = PluginTestSupport.manager();
        manager.close();
        EventListenerRegistry events =
                ReflectionTestUtils.invokeMethod(
                        manager,
                        "preparedEvents",
                        catalog,
                        managed,
                        new EventListenerRegistry.Preparation());
        if (events == null) throw new AssertionError("Prepared event registry must exist");
        var constructor =
                PluginManager.PublishedState.class.getDeclaredConstructor(
                        List.class,
                        List.class,
                        List.class,
                        List.class,
                        ContributionCatalog.class,
                        EventListenerRegistry.class,
                        Map.class);
        constructor.setAccessible(true);
        var publication =
                constructor.newInstance(
                        managed, List.of(), List.of(), List.of(), catalog, events, Map.of());
        ReflectionTestUtils.setField(manager, "published", publication);
        return manager;
    }

    @Test
    void chainsEveryContributionInCatalogOrder() throws Exception {
        try (var manager =
                managerWith(
                        new MiddlewarePlugin(
                                "fixture.first",
                                middleware((observation, cancellation) -> observation + "|first")),
                        new MiddlewarePlugin(
                                "fixture.second",
                                middleware(
                                        (observation, cancellation) -> observation + "|second")))) {
            assertEquals(
                    "observation|first|second", manager.applyObservationMiddleware("observation"));
        }
    }

    @Test
    void returnsTheTextUnchangedWithoutContributions() throws Exception {
        try (var manager = managerWith()) {
            assertEquals("observation", manager.applyObservationMiddleware("observation"));
        }
    }

    @Test
    void middlewareFailurePropagatesAsIllegalState() throws Exception {
        try (var manager =
                managerWith(
                        new MiddlewarePlugin(
                                "fixture.failing",
                                middleware(
                                        (observation, cancellation) -> {
                                            throw new PluginFailure(
                                                    PluginFailure.Code.INTERNAL_FAILURE);
                                        })))) {
            assertThrows(
                    IllegalStateException.class,
                    () -> manager.applyObservationMiddleware("observation"));
        }
    }
}
