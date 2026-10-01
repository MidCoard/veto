package top.focess.veto.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.event.BeforeToolEvent;
import top.focess.veto.api.event.Event;
import top.focess.veto.api.event.EventHandler;
import top.focess.veto.api.event.EventPriority;
import top.focess.veto.api.event.LifecycleEvent;
import top.focess.veto.api.event.Listener;
import top.focess.veto.api.event.UserAuthenticatedEvent;
import top.focess.veto.api.event.UserLoggedInEvent;
import top.focess.veto.api.event.UserRegisteredEvent;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contribution.Contribution;
import top.focess.veto.api.plugin.contribution.ContributionCatalog;
import top.focess.veto.api.plugin.contribution.ContributionSource;

class EventListenerRegistryTest {
    @Test
    void broadcastRejectsWorkflowEventsBeforeInvokingHandlers() {
        var calls = new ArrayList<String>();
        var registry = registry(new NormalProbe(calls, "listener"));
        var event =
                new BeforeToolEvent(
                        "owner",
                        "session",
                        "agent",
                        () -> false,
                        new BeforeToolEvent.Invocation(
                                "tool", "call", new JsonValue.ObjectValue(Map.of())));
        assertThrows(IllegalArgumentException.class, () -> registry.broadcast(event));
        assertThrows(
                IllegalArgumentException.class,
                () -> registry.broadcast(event, Set.of("demo.listener")));
        assertTrue(calls.isEmpty());
    }

    @Test
    void pluginAssertionIsContainedButFatalVmErrorsPropagate() {
        var calls = new ArrayList<String>();
        var ordinary = registry(new ErrorProbe(false), new NormalProbe(calls, "later"));
        ordinary.broadcast(
                new UserLoggedInEvent(new Scope.UserScope("owner")), Set.of("demo.listener"));
        assertEquals(List.of("later"), calls);
        var event =
                new BeforeToolEvent(
                        "owner",
                        "session",
                        "agent",
                        () -> false,
                        new BeforeToolEvent.Invocation(
                                "tool", "call", new JsonValue.ObjectValue(Map.of())));
        var failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> ordinary.submit(event, Set.of("demo.listener")));
        assertEquals("Workflow listener unavailable", failure.getMessage());
        var fatal = registry(new ErrorProbe(true));
        assertThrows(
                InternalError.class,
                () ->
                        fatal.broadcast(
                                new UserLoggedInEvent(new Scope.UserScope("owner")),
                                Set.of("demo.listener")));
    }

    private static final class ErrorProbe extends Listener {
        private final boolean fatal;

        private ErrorProbe(boolean fatal) {
            this.fatal = fatal;
        }

        private void fail() {
            if (fatal) throw new InternalError("synthetic fatal error");
            throw new AssertionError("private plugin diagnostic");
        }

        @EventHandler
        public void lifecycle(@NonNull UserLoggedInEvent event) {
            fail();
        }

        @EventHandler
        public void workflow(@NonNull BeforeToolEvent event) {
            fail();
        }
    }

    @Test
    void preparationRejectsStaticHandlersBeforeAnyDispatch() {
        var preparation = new EventListenerRegistry.Preparation();
        var error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> preparation.prepare(new StaticProbe()));
        assertEquals("@EventHandler must be an instance method", error.getMessage());
    }

    @Test
    void preparedBroadcastChecksOnlyItsRouteAndSkipsInactiveOwners() {
        var calls = new ArrayList<String>();
        var source =
                new ContributionSource("demo.listener", "1.0.0", ContributionSource.Origin.PLUGIN);
        var listener = new NormalProbe(calls, "active");
        var preparation = new EventListenerRegistry.Preparation();
        preparation.prepare(listener);
        var catalog =
                new ContributionCatalog.Builder()
                        .define(StandardContributionPoints.LISTENERS, ignored -> {})
                        .stage(
                                source,
                                List.of(
                                        Contribution.of(
                                                StandardContributionPoints.LISTENERS,
                                                "probe",
                                                listener)))
                        .freeze();
        var active = new AtomicBoolean(true);
        var registry =
                EventListenerRegistry.build(
                        catalog,
                        (namespace, body) -> {
                            try {
                                body.run();
                            } catch (Exception failure) {
                                throw new IllegalStateException(failure);
                            }
                        },
                        preparation,
                        namespace -> active.get());
        registry.broadcast(new UserRegisteredEvent(new Scope.UserScope("owner")));
        assertTrue(calls.isEmpty());
        registry.broadcast(new UserLoggedInEvent(new Scope.UserScope("owner")));
        assertEquals(List.of("active"), calls);
        active.set(false);
        registry.broadcast(new UserLoggedInEvent(new Scope.UserScope("owner")));
        assertEquals(List.of("active"), calls);
    }

    private static final class StaticProbe extends Listener {
        @EventHandler
        public static void event(@NonNull UserLoggedInEvent event) {}
    }

    @Test
    void dispatchFiltersSelectionAndSortsPriorityBeforeContributionOrder() {
        var calls = new ArrayList<String>();
        var registry =
                registry(
                        new LowProbe(calls), new NormalProbe(calls, "first"),
                        new NormalProbe(calls, "second"), new HighProbe(calls));
        registry.broadcast(new UserLoggedInEvent(new Scope.UserScope("owner")), Set.of());
        assertTrue(calls.isEmpty());
        registry.broadcast(
                new UserLoggedInEvent(new Scope.UserScope("owner")), Set.of("demo.listener"));
        assertEquals(List.of("high", "first", "second", "low"), calls);
    }

    @Test
    void preventedToolVetoCannotBeClearedByAnOptedInParentObserver() {
        var calls = new ArrayList<String>();
        var registry = registry(new RejectProbe(calls));
        var event =
                new BeforeToolEvent(
                        "owner",
                        "session",
                        "agent",
                        () -> false,
                        new BeforeToolEvent.Invocation(
                                "tool", "call", new JsonValue.ObjectValue(Map.of())));
        registry.submit(event, Set.of("demo.listener"));
        assertEquals(List.of("reject", "observe"), calls);
        assertTrue(event.isPrevent());
        assertEquals(BeforeToolEvent.Decision.REJECT, event.decision());
    }

    @Test
    void workflowFailureIsSanitizedWhileLifecycleFailureDoesNotStopLaterHandlers() {
        var calls = new ArrayList<String>();
        var registry = registry(new FailureProbe(), new NormalProbe(calls, "later"));
        var event =
                new BeforeToolEvent(
                        "owner",
                        "session",
                        "agent",
                        () -> false,
                        new BeforeToolEvent.Invocation(
                                "tool", "call", new JsonValue.ObjectValue(Map.of())));
        var failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> registry.submit(event, Set.of("demo.listener")));
        assertEquals("Workflow listener unavailable", failure.getMessage());
        registry.broadcast(
                new UserLoggedInEvent(new Scope.UserScope("owner")), Set.of("demo.listener"));
        assertEquals(List.of("later"), calls);
    }

    private static @NonNull EventListenerRegistry registry(
            @NonNull Listener @NonNull ... listeners) {
        var source =
                new ContributionSource("demo.listener", "1.0.0", ContributionSource.Origin.PLUGIN);
        var contributions = new ArrayList<@NonNull Contribution<?>>();
        for (int index = 0; index < listeners.length; index++) {
            contributions.add(
                    Contribution.of(
                            StandardContributionPoints.LISTENERS,
                            "probe-" + index,
                            listeners[index]));
        }
        var catalog =
                new ContributionCatalog.Builder()
                        .define(StandardContributionPoints.LISTENERS, ignored -> {})
                        .stage(source, contributions)
                        .freeze();
        return EventListenerRegistry.build(
                catalog,
                (namespace, body) -> {
                    try {
                        body.run();
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                });
    }

    private static final class NormalProbe extends Listener {
        private final @NonNull List<String> calls;
        private final @NonNull String label;

        private NormalProbe(@NonNull List<String> calls, @NonNull String label) {
            this.calls = calls;
            this.label = label;
        }

        @EventHandler
        public void event(@NonNull UserLoggedInEvent event) {
            calls.add(label);
        }
    }

    private static final class HighProbe extends Listener {
        private final @NonNull List<String> calls;

        private HighProbe(@NonNull List<String> calls) {
            this.calls = calls;
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void event(@NonNull UserLoggedInEvent event) {
            calls.add("high");
        }
    }

    private static final class LowProbe extends Listener {
        private final @NonNull List<String> calls;

        private LowProbe(@NonNull List<String> calls) {
            this.calls = calls;
        }

        @EventHandler(priority = EventPriority.LOWEST)
        public void event(@NonNull UserLoggedInEvent event) {
            calls.add("low");
        }
    }

    private static final class RejectProbe extends Listener {
        private final @NonNull List<String> calls;

        private RejectProbe(@NonNull List<String> calls) {
            this.calls = calls;
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void reject(@NonNull BeforeToolEvent event) {
            calls.add("reject");
            event.reject();
        }

        @EventHandler(priority = EventPriority.LOWEST)
        public void skipped(@NonNull BeforeToolEvent event) {
            calls.add("skipped");
        }

        @EventHandler(notCallIfPrevented = false)
        public void observe(@NonNull Event event) {
            calls.add("observe");
            event.setPrevent(false);
            if (event instanceof BeforeToolEvent tool)
                tool.decide(BeforeToolEvent.Decision.CONTINUE);
        }
    }

    private static final class FailureProbe extends Listener {
        @EventHandler
        public void failTool(@NonNull BeforeToolEvent event) {
            throw new IllegalStateException("private-input");
        }

        @EventHandler
        public void failLifecycle(@NonNull UserLoggedInEvent event) {
            throw new IllegalStateException("private-input");
        }
    }

    @Test
    void registrationAndLoginReachTheirSpecificAndSharedHandlers() {
        var calls = new ArrayList<String>();
        var source =
                new ContributionSource("demo.listener", "1.0.0", ContributionSource.Origin.PLUGIN);
        var catalog =
                new ContributionCatalog.Builder()
                        .define(StandardContributionPoints.LISTENERS, ignored -> {})
                        .stage(
                                source,
                                List.of(
                                        Contribution.of(
                                                StandardContributionPoints.LISTENERS,
                                                "probe",
                                                new Probe(calls))))
                        .freeze();
        var registry =
                EventListenerRegistry.build(
                        catalog,
                        (namespace, body) -> {
                            try {
                                body.run();
                            } catch (Exception failure) {
                                throw new IllegalStateException(failure);
                            }
                        });
        registry.broadcast(
                new UserRegisteredEvent(new Scope.UserScope("owner")), Set.of("demo.listener"));
        assertEquals(List.of("registered", "authenticated", "lifecycle", "event"), calls);
        calls.clear();
        registry.broadcast(
                new UserLoggedInEvent(new Scope.UserScope("owner")), Set.of("demo.listener"));
        assertEquals(List.of("logged-in", "authenticated", "lifecycle", "event"), calls);
    }

    private static final class Probe extends Listener {
        private final @NonNull List<String> calls;

        private Probe(@NonNull List<String> calls) {
            this.calls = calls;
        }

        @EventHandler
        public void registered(@NonNull UserRegisteredEvent event) {
            calls.add("registered");
        }

        @EventHandler
        public void loggedIn(@NonNull UserLoggedInEvent event) {
            calls.add("logged-in");
        }

        @EventHandler
        public void authenticated(@NonNull UserAuthenticatedEvent event) {
            calls.add("authenticated");
        }

        @EventHandler
        public void lifecycle(@NonNull LifecycleEvent event) {
            calls.add("lifecycle");
        }

        @EventHandler
        public void event(@NonNull Event event) {
            calls.add("event");
        }
    }
}
