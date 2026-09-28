package top.focess.veto.integration.plugins;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.event.BeforeInputEvent;
import top.focess.veto.api.event.EventHandler;
import top.focess.veto.api.event.Listener;
import top.focess.veto.api.plugin.contract.*;
import top.focess.veto.api.plugin.contribution.*;
import top.focess.veto.plugin.runtime.*;

class WorkflowHooksTest {
    private static @NonNull BeforeInputEvent input(boolean cancelled, @NonNull String text) {
        return new BeforeInputEvent("owner", "session", "agent", () -> cancelled, text);
    }

    /** Appends a fixed suffix; optionally counts how many times it ran. */
    public static final class AppendListener extends Listener {
        private final AtomicInteger calls;
        private final @NonNull String suffix;

        public AppendListener(AtomicInteger calls, @NonNull String suffix) {
            this.calls = calls;
            this.suffix = suffix;
        }

        /** Transforms the input text. */
        @EventHandler
        public void onInput(@NonNull BeforeInputEvent event) {
            if (calls != null) calls.incrementAndGet();
            event.setText(event.text() + suffix);
        }
    }

    /** Always throws, to verify a handler failure is sanitized and halts the chain. */
    public static final class FailingListener extends Listener {
        private final @NonNull AtomicInteger calls;

        public FailingListener(@NonNull AtomicInteger calls) {
            this.calls = calls;
        }

        /** Throws a sensitive payload that must never reach the caller. */
        @EventHandler
        public void onInput(@NonNull BeforeInputEvent event) {
            calls.incrementAndGet();
            throw new IllegalStateException("sensitive payload");
        }
    }

    @Test
    void orderingSelectionCancellationAndLifecycleAreEnforced() throws Exception {
        var calls = new AtomicInteger();
        Listener first = new AppendListener(calls, "A");
        Listener second = new AppendListener(null, "B");
        var point = StandardContributionPoints.LISTENERS;
        try (var fixture =
                new WorkflowPluginFixture(
                        List.of(
                                new Contribution<>(
                                        point,
                                        "second",
                                        second,
                                        Set.of(),
                                        Set.of(new ContributionId("fixture.workflow:first"))),
                                Contribution.of(point, "first", first)))) {
            var event = input(false, "");
            fixture.sessions.dispatch(event);
            assertEquals("AB", event.text());
            assertEquals(1, calls.get());
            assertThrows(
                    IllegalStateException.class, () -> fixture.sessions.dispatch(input(true, "")));
            assertEquals(1, calls.get());
            SessionPlugins none = spy(fixture.sessions);
            doReturn(List.of()).when(none).bindings("session");
            var unchanged = input(false, "unchanged");
            none.dispatch(unchanged);
            assertEquals("unchanged", unchanged.text());
            fixture.runtime.close();
            assertThrows(
                    IllegalStateException.class, () -> fixture.sessions.dispatch(input(false, "")));
        }
    }

    @Test
    void failuresDoNotLeakPluginPayloadOrRunFollowingHooks() throws Exception {
        var calls = new AtomicInteger();
        var after = new AtomicInteger();
        Listener bad = new FailingListener(calls);
        Listener following = new AppendListener(after, "B");
        var point = StandardContributionPoints.LISTENERS;
        try (var fixture =
                new WorkflowPluginFixture(
                        List.of(
                                Contribution.of(point, "bad", bad),
                                new Contribution<>(
                                        point,
                                        "following",
                                        following,
                                        Set.of(),
                                        Set.of(new ContributionId("fixture.workflow:bad")))))) {
            var error =
                    assertThrows(
                            IllegalStateException.class,
                            () -> fixture.sessions.dispatch(input(false, "")));
            assertEquals("Workflow listener unavailable", error.getMessage());
            assertNull(error.getCause());
            assertEquals(1, calls.get());
            assertEquals(0, after.get());
        }
    }

    @Test
    void preventedEventCannotBeRestoredByOptedInObserver() throws Exception {
        var observed = new AtomicBoolean();
        var following = new AtomicInteger();
        Listener veto =
                new Listener() {
                    @EventHandler
                    public void onInput(@NonNull BeforeInputEvent event) {
                        event.prevent();
                    }
                };
        Listener observer =
                new Listener() {
                    @EventHandler(notCallIfPrevented = false)
                    public void onInput(@NonNull BeforeInputEvent event) {
                        observed.set(event.isPrevent());
                        event.setPrevent(false);
                    }
                };
        Listener skipped = new AppendListener(following, "unexpected");
        try (var fixture =
                new WorkflowPluginFixture(
                        List.of(
                                Contribution.of(StandardContributionPoints.LISTENERS, "veto", veto),
                                new Contribution<>(
                                        StandardContributionPoints.LISTENERS,
                                        "observer",
                                        observer,
                                        Set.of(),
                                        Set.of(new ContributionId("fixture.workflow:veto"))),
                                new Contribution<>(
                                        StandardContributionPoints.LISTENERS,
                                        "skipped",
                                        skipped,
                                        Set.of(),
                                        Set.of(
                                                new ContributionId(
                                                        "fixture.workflow:observer")))))) {
            var event = input(false, "original");
            fixture.sessions.dispatch(event);
            assertTrue(observed.get());
            assertTrue(event.isPrevent());
            assertEquals(0, following.get());
            assertEquals("original", event.text());
        }
    }
}
