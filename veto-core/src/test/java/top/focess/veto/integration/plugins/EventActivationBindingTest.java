package top.focess.veto.integration.plugins;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import top.focess.veto.api.event.BeforeInputEvent;
import top.focess.veto.api.event.EventHandler;
import top.focess.veto.api.event.Listener;
import top.focess.veto.api.event.UserLoggedInEvent;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.VetoPlugin;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.PluginFailure;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contribution.Contribution;
import top.focess.veto.api.plugin.contribution.ContributionCatalog;
import top.focess.veto.api.plugin.contribution.ContributionSource;
import top.focess.veto.event.EventListenerRegistry;
import top.focess.veto.plugin.runtime.ManagedPlugin;

class EventActivationBindingTest {
    @Test
    void mutatingBuildOwnerMapCannotRetargetPreparedAdmission() throws Exception {
        var originalProbe = new Probe(false);
        var replacementProbe = new Probe(false);
        try (var original = new Activation(originalProbe);
                var replacement = new Activation(replacementProbe)) {
            var owners = new HashMap<@NonNull String, @NonNull ManagedPlugin>();
            owners.put("fixture.activation", original.runtime);
            var routes =
                    EventListenerRegistry.build(
                            original.catalog, owners, new EventListenerRegistry.Preparation());
            owners.put("fixture.activation", replacement.runtime);
            replacement.runtime.close();

            routes.submit(input(), Set.of("fixture.activation"));
            assertEquals(1, originalProbe.calls.get());
            assertEquals(0, replacementProbe.calls.get());
        }
    }

    @Test
    void capturedRoutesKeepExactActivationWhenAnotherOwnerHasTheSameIdentity() throws Exception {
        var oldProbe = new Probe(false);
        var replacementProbe = new Probe(false);
        try (var oldActivation = new Activation(oldProbe);
                var replacement = new Activation(replacementProbe)) {
            var oldRoutes = oldActivation.events;
            oldRoutes.submit(login());
            replacement.events.submit(login());
            assertEquals(1, oldProbe.calls.get());
            assertEquals(1, replacementProbe.calls.get());

            oldActivation.runtime.close();
            oldRoutes.submit(login());
            assertEquals(1, oldProbe.calls.get());
            assertEquals(1, replacementProbe.calls.get());
            assertDoesNotThrow(() -> oldRoutes.submit(input(), Set.of("fixture.activation")));
            assertEquals(1, oldProbe.calls.get());
            assertEquals(1, replacementProbe.calls.get());

            replacement.events.submit(input(), Set.of("fixture.activation"));
            replacement.events.submit(login());
            assertEquals(3, replacementProbe.calls.get());
            assertEquals(1, oldProbe.calls.get());
        }
    }

    @Test
    void checkedHandlerFailureIsContainedAndReleasesRealAdmission() throws Exception {
        var probe = new Probe(true);
        try (var activation = new Activation(probe)) {
            assertDoesNotThrow(
                    () -> activation.events.submit(input(), Set.of("fixture.activation")));
            assertEquals(1, probe.calls.get());
            activation.events.submit(login());
            assertEquals(2, probe.calls.get());
            assertTrue(activation.runtime.execute(() -> true));
        }
    }

    private static @NonNull UserLoggedInEvent login() {
        return new UserLoggedInEvent(new Scope.UserScope("owner"));
    }

    private static @NonNull BeforeInputEvent input() {
        return new BeforeInputEvent(new Scope.AgentScope("owner", "session", "agent"), "input");
    }

    private static final class Probe implements Listener {
        private final @NonNull AtomicInteger calls = new AtomicInteger();
        private final boolean failWorkflow;

        private Probe(boolean failWorkflow) {
            this.failWorkflow = failWorkflow;
        }

        @EventHandler
        public void input(@NonNull BeforeInputEvent event) throws Exception {
            calls.incrementAndGet();
            if (failWorkflow) throw new Exception("private plugin input");
        }

        @EventHandler
        public void login(@NonNull UserLoggedInEvent event) {
            calls.incrementAndGet();
        }
    }

    private static final class Activation implements AutoCloseable {
        private final @NonNull ExecutorService control = Executors.newSingleThreadExecutor();
        private final @NonNull ManagedPlugin runtime;
        private final @NonNull ContributionCatalog catalog;
        private final @NonNull EventListenerRegistry events;

        private Activation(@NonNull Listener listener) throws PluginFailure {
            var implementation =
                    new VetoPlugin() {
                        @Override
                        public @NonNull PluginIdentity identity() {
                            return new PluginIdentity("fixture.activation", "1.0.0");
                        }

                        @Override
                        public void start() {}

                        @Override
                        public void close() {}
                    };
            runtime = new ManagedPlugin(implementation, control);
            runtime.construct(
                    new PluginContext(
                            runtime.identity(), () -> {}, runtime::state, Map.of(), Map.of()),
                    new JsonValue.ObjectValue(Map.of()));
            runtime.start();
            catalog =
                    new ContributionCatalog.Builder()
                            .define(StandardContributionPoints.LISTENERS, ignored -> {})
                            .stage(
                                    new ContributionSource(
                                            runtime.identity().id(),
                                            runtime.identity().version(),
                                            ContributionSource.Origin.PLUGIN),
                                    List.of(
                                            Contribution.of(
                                                    StandardContributionPoints.LISTENERS,
                                                    "probe",
                                                    listener)))
                            .freeze();
            EventListenerRegistry prepared =
                    ReflectionTestUtils.invokeMethod(
                            PluginManager.class,
                            "preparedEvents",
                            catalog,
                            List.of(runtime),
                            new EventListenerRegistry.Preparation());
            if (prepared == null) throw new AssertionError("Prepared event registry must exist");
            events = prepared;
        }

        @Override
        public void close() {
            try {
                runtime.close();
            } finally {
                control.shutdown();
            }
        }
    }
}
