package top.focess.veto.integration.plugins;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import top.focess.veto.api.plugin.AbstractVetoPlugin;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginContributions;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.ObservationMiddleware;
import top.focess.veto.api.plugin.contract.PluginFailure;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contribution.Contribution;
import top.focess.veto.api.plugin.contribution.ContributionCatalog;
import top.focess.veto.api.plugin.contribution.ContributionSource;
import top.focess.veto.plugin.runtime.*;

/**
 * The session-less {@code veto:observation-middleware} chain in {@link
 * PluginManager#applyObservationMiddleware}: catalog ordering across contributions, identity when
 * no middleware is registered, and failure propagation. The real secret-protection plugin's
 * middleware is covered by {@link PluginManagerDiscoveryTest}.
 */
class ApplyObservationMiddlewareTest {
    private @Nullable ExecutorService executor;

    @AfterEach
    void stopExecutor() {
        if (executor != null) executor.shutdownNow();
    }

    private static final class MiddlewarePlugin extends AbstractVetoPlugin {
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

        @Override
        protected @NonNull PluginContributions onInitialize(
                @NonNull PluginContext context, JsonValue.@NonNull ObjectValue configuration) {
            return new PluginContributions(
                    List.of(
                            Contribution.of(
                                    StandardContributionPoints.OBSERVATION,
                                    "middleware",
                                    middleware)));
        }

        @Override
        protected void onStart() {}

        @Override
        protected void onClose() {}
    }

    /**
     * A manager whose plugin list and catalog are replaced by the given stub middleware plugins.
     */
    private @NonNull PluginManager managerWith(@NonNull MiddlewarePlugin @NonNull ... stubs)
            throws Exception {
        var lifecycle = Executors.newSingleThreadExecutor();
        executor = lifecycle;
        List<ManagedPlugin> managed = new ArrayList<>();
        var builder = new ContributionCatalog.Builder();
        builder.define(StandardContributionPoints.OBSERVATION, ignored -> {});
        for (var stub : stubs) {
            var plugin = new ManagedPlugin(stub, lifecycle);
            var contributions =
                    plugin.initialize(
                            new PluginContext(stub.identity()),
                            new JsonValue.ObjectValue(Map.of()));
            builder.stage(
                    new ContributionSource(
                            stub.identity().id(),
                            stub.identity().version(),
                            ContributionSource.Origin.PLUGIN),
                    contributions.entries());
            managed.add(plugin);
        }
        var catalog = builder.freeze();
        for (var plugin : managed) plugin.start();
        var manager = PluginTestSupport.manager();
        manager.close();
        ReflectionTestUtils.setField(manager, "plugins", List.copyOf(managed));
        ReflectionTestUtils.setField(manager, "catalog", catalog);
        return manager;
    }

    @Test
    void chainsEveryContributionInCatalogOrder() throws Exception {
        try (var manager =
                managerWith(
                        new MiddlewarePlugin(
                                "fixture.first",
                                (observation, cancellation) -> observation + "|first"),
                        new MiddlewarePlugin(
                                "fixture.second",
                                (observation, cancellation) -> observation + "|second"))) {
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
                                (observation, cancellation) -> {
                                    throw new PluginFailure(PluginFailure.Code.INTERNAL_FAILURE);
                                }))) {
            assertThrows(
                    IllegalStateException.class,
                    () -> manager.applyObservationMiddleware("observation"));
        }
    }
}
