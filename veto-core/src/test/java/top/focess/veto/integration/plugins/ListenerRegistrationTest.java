package top.focess.veto.integration.plugins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.List;
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
import top.focess.veto.api.plugin.contract.AgentInbox;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contribution.Contribution;
import top.focess.veto.api.plugin.contribution.ContributionPoint;
import top.focess.veto.plugin.runtime.ManagedPlugin;

class ListenerRegistrationTest {
    @Test
    void malformedLateRegistrationDoesNotPublishAndCanBeReplacedWithValidListener()
            throws Exception {
        try (var manager = PluginTestSupport.manager()) {
            var previousPublication = manager.registry();
            var plugin = previousPublication.plugins().getFirst();
            var context = registrationContext(manager, plugin);
            var previousEvents = previousPublication.events();
            var previousEntries = previousPublication.entries(StandardContributionPoints.LISTENERS);
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            context.register(
                                    StandardContributionPoints.LISTENERS,
                                    "prepared-probe",
                                    new InvalidProbe()));
            assertSame(previousEvents, manager.registry().events());
            assertSame(previousPublication, manager.registry());
            assertEquals(
                    previousEntries,
                    manager.registry().entries(StandardContributionPoints.LISTENERS));
            var calls = new AtomicInteger();
            context.register(
                    StandardContributionPoints.LISTENERS, "prepared-probe", new ValidProbe(calls));
            assertSame(plugin, previousPublication.plugin(plugin.identity().id()));
            assertTrue(
                    previousPublication.entries(StandardContributionPoints.LISTENERS).stream()
                            .noneMatch(entry -> entry.id().localId().equals("prepared-probe")));
            var nextPublication = manager.registry();
            assertSame(plugin, nextPublication.plugin(plugin.identity().id()));
            assertTrue(
                    nextPublication.entries(StandardContributionPoints.LISTENERS).stream()
                            .anyMatch(entry -> entry.id().localId().equals("prepared-probe")));
            manager.registry()
                    .events()
                    .submit(new UserLoggedInEvent(new Scope.UserScope("probe-user")));
            assertEquals(1, calls.get());
            assertTrue(
                    manager.registry().entries(StandardContributionPoints.LISTENERS).stream()
                            .anyMatch(entry -> entry.id().localId().equals("prepared-probe")));
        }
    }

    @Test
    void capturedRegistryPointMetadataDoesNotChangeAfterLateRegistration() throws Exception {
        try (var manager = PluginTestSupport.manager()) {
            var previous = manager.registry();
            var pointId = StandardContributionPoints.AGENT_INBOX.id().value();
            var plugin =
                    previous.plugins().stream()
                            .filter(
                                    candidate ->
                                            !previous.pointIds(candidate.identity().id())
                                                    .contains(pointId))
                            .findFirst()
                            .orElseThrow();
            var id = plugin.identity().id();
            var points = previous.pointIds(id);
            var context = registrationContext(manager, plugin);
            context.register(
                    StandardContributionPoints.AGENT_INBOX,
                    "metadata-probe",
                    mock(AgentInbox.class));
            var next = manager.registry();
            assertEquals(points, previous.pointIds(id));
            assertFalse(previous.pointIds(id).contains(pointId));
            assertTrue(next.pointIds(id).contains(pointId));
            assertSame(plugin, next.plugin(id));
            assertTrue(
                    next.entries(StandardContributionPoints.AGENT_INBOX).stream()
                            .anyMatch(entry -> entry.id().localId().equals("metadata-probe")));
            assertTrue(
                    previous.entries(StandardContributionPoints.AGENT_INBOX).stream()
                            .noneMatch(entry -> entry.id().localId().equals("metadata-probe")));
        }
    }

    private static @NonNull PluginContext registrationContext(
            @NonNull PluginManager manager, @NonNull ManagedPlugin plugin) {
        var staged = ReflectionTestUtils.getField(manager, "stagedRegistrations");
        if (!(staged instanceof List<?> registrations)) throw new AssertionError("Missing staging");
        for (var registration : registrations) {
            if (registration == null) throw new AssertionError("Missing registration");
            var owner = ReflectionTestUtils.invokeMethod(registration, "plugin");
            if (owner != plugin) continue;
            var points = ReflectionTestUtils.invokeMethod(registration, "points");
            if (points == null) throw new AssertionError("Missing registration points");
            Map<@NonNull ContributionPoint<?>, @NonNull Consumer<@NonNull Contribution<?>>>
                    handlers =
                            ReflectionTestUtils.invokeMethod(
                                    manager, "contributionHandlers", plugin, points);
            if (handlers == null) throw new AssertionError("Registration handlers must exist");
            return new PluginContext(
                    plugin.identity(), () -> {}, plugin::state, Map.of(), handlers);
        }
        throw new AssertionError("Registration is missing");
    }

    private static final class InvalidProbe implements Listener {
        @EventHandler
        public static void event(@NonNull UserLoggedInEvent event) {}
    }

    private static final class ValidProbe implements Listener {
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
