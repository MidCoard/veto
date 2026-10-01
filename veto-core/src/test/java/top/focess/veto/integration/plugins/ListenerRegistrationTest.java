package top.focess.veto.integration.plugins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import top.focess.veto.api.event.EventHandler;
import top.focess.veto.api.event.Listener;
import top.focess.veto.api.event.UserLoggedInEvent;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contribution.Contribution;
import top.focess.veto.api.plugin.contribution.ContributionPoint;

class ListenerRegistrationTest {
    @Test
    void malformedLateRegistrationDoesNotPublishAndCanBeReplacedWithValidListener()
            throws Exception {
        try (var manager = PluginTestSupport.manager()) {
            var registration = manager.registrations().getFirst();
            var plugin = registration.plugin();
            Map<@NonNull ContributionPoint<?>, @NonNull Consumer<@NonNull Contribution<?>>>
                    handlers =
                            ReflectionTestUtils.invokeMethod(
                                    manager, "contributionHandlers", plugin, registration.points());
            if (handlers == null) throw new AssertionError("Registration handlers must exist");
            var context =
                    new PluginContext(
                            plugin.identity(), () -> {}, plugin::state, Map.of(), handlers);
            var previousCatalog = manager.catalog();
            var previousEvents = manager.events();
            var previousEntries = registration.entries();
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            context.register(
                                    StandardContributionPoints.LISTENERS,
                                    "prepared-probe",
                                    new InvalidProbe()));
            assertSame(previousCatalog, manager.catalog());
            assertSame(previousEvents, manager.events());
            assertEquals(previousEntries, registration.entries());
            var calls = new AtomicInteger();
            context.register(
                    StandardContributionPoints.LISTENERS, "prepared-probe", new ValidProbe(calls));
            manager.events().broadcast(new UserLoggedInEvent(new Scope.UserScope("probe-user")));
            assertEquals(1, calls.get());
            assertTrue(
                    manager.catalog().entries(StandardContributionPoints.LISTENERS).stream()
                            .anyMatch(entry -> entry.id().localId().equals("prepared-probe")));
        }
    }

    private static final class InvalidProbe extends Listener {
        @EventHandler
        public static void event(@NonNull UserLoggedInEvent event) {}
    }

    private static final class ValidProbe extends Listener {
        private final @NonNull AtomicInteger calls;

        private ValidProbe(@NonNull AtomicInteger calls) {
            this.calls = calls;
        }

        @EventHandler
        public void event(@NonNull UserLoggedInEvent event) {
            calls.incrementAndGet();
        }
    }
}
