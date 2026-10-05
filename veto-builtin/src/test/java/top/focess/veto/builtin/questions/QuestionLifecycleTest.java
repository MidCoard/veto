package top.focess.veto.builtin.questions;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.focess.veto.api.event.AgentTerminatedEvent;
import top.focess.veto.api.event.SessionDeletedEvent;
import top.focess.veto.api.event.UserLogoutEvent;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.Scope;

class QuestionLifecycleTest {
    @Test
    void identitiesIncludeOwnerSessionAgentAndCall() {
        var runtime = new QuestionRuntime(mock(PluginHost.class));
        var original =
                new PluginHost.Invocation(
                        UUID.fromString("36fc510c-70b8-5be2-b3cc-c9d1bc0c6376"),
                        "session",
                        "agent",
                        "request",
                        "call");
        var pending = runtime.register(original, List.of());
        for (var scope :
                List.of(
                        new Scope.AgentScope(
                                UUID.fromString("ede9d700-cf06-5666-9e12-b8cb22e3da12"),
                                "session",
                                "agent"),
                        new Scope.AgentScope(
                                UUID.fromString("36fc510c-70b8-5be2-b3cc-c9d1bc0c6376"),
                                "other",
                                "agent"),
                        new Scope.AgentScope(
                                UUID.fromString("36fc510c-70b8-5be2-b3cc-c9d1bc0c6376"),
                                "session",
                                "other"))) {
            assertTrue(runtime.pendingFor(scope).isEmpty());
            assertFalse(runtime.answer(scope, "call", Map.of()));
            assertFalse(runtime.cancel(scope, "call"));
        }
        assertFalse(pending.isDone());
        assertTrue(
                runtime.cancel(
                        new Scope.AgentScope(
                                UUID.fromString("36fc510c-70b8-5be2-b3cc-c9d1bc0c6376"),
                                "session",
                                "agent"),
                        "call"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"userId", "session", "agent", "plugin"})
    void lifecycleCancelsOnlyMatchingBatches(@NonNull String transition) {
        var runtime = new QuestionRuntime(mock(PluginHost.class));
        var target = runtime.register(QuestionTestSupport.invocation("agent", "call"), List.of());
        var other =
                runtime.register(
                        new PluginHost.Invocation(
                                UUID.fromString("ede9d700-cf06-5666-9e12-b8cb22e3da12"),
                                "other-session",
                                "agent",
                                null,
                                "call"),
                        List.of());
        switch (transition) {
            case "userId" ->
                    runtime.onUserLogout(
                            new UserLogoutEvent(
                                    new Scope.UserScope(
                                            UUID.fromString(
                                                    "36fc510c-70b8-5be2-b3cc-c9d1bc0c6376"))));
            case "session" ->
                    runtime.onSessionDeleted(
                            new SessionDeletedEvent(
                                    new Scope.SessionScope(
                                            UUID.fromString("36fc510c-70b8-5be2-b3cc-c9d1bc0c6376"),
                                            "session")));
            case "agent" ->
                    runtime.onAgentTerminated(
                            new AgentTerminatedEvent(
                                    new Scope.AgentScope(
                                            UUID.fromString("36fc510c-70b8-5be2-b3cc-c9d1bc0c6376"),
                                            "session",
                                            "agent")));
            case "plugin" -> runtime.close();
            default -> throw new AssertionError();
        }
        assertTrue(target.join().cancelled());
        assertEquals(transition.equals("plugin"), other.isDone());
        runtime.close();
        assertTrue(other.join().cancelled());
        assertThrows(
                IllegalStateException.class,
                () -> runtime.register(QuestionTestSupport.invocation("agent", "late"), List.of()));
    }

    @Test
    void registrationRacingStopCannotLeavePendingWaiters() throws Exception {
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 50; i++) {
                var runtime = new QuestionRuntime(mock(PluginHost.class));
                var registered =
                        threads.submit(
                                () -> {
                                    try {
                                        return runtime.register(
                                                QuestionTestSupport.invocation("agent", "call"),
                                                List.of());
                                    } catch (IllegalStateException closed) {
                                        return null;
                                    }
                                });
                var stopped = threads.submit(runtime::close);
                stopped.get(2, TimeUnit.SECONDS);
                var pending = registered.get(2, TimeUnit.SECONDS);
                if (pending != null) assertTrue(pending.get(2, TimeUnit.SECONDS).cancelled());
                assertTrue(runtime.pendingFor(QuestionTestSupport.scope("agent")).isEmpty());
            }
        }
    }

    @Test
    void registrationAndExceptionalCleanupInvalidateButRejectedAnswersDoNot() {
        var host = mock(PluginHost.class);
        var runtime = new QuestionRuntime(host);
        var sessionScope = QuestionTestSupport.scope("agent").sessionScope();
        var pending = runtime.register(QuestionTestSupport.invocation("agent", "call"), List.of());
        verify(host).invalidate(sessionScope, "interactions");
        verifyNoMoreInteractions(host);
        clearInvocations(host);
        assertFalse(runtime.answer(QuestionTestSupport.scope("agent"), "missing", Map.of()));
        verifyNoInteractions(host);
        pending.completeExceptionally(new IllegalStateException("interrupted"));
        assertTrue(runtime.pendingFor(QuestionTestSupport.scope("agent")).isEmpty());
        verify(host).invalidate(sessionScope, "interactions");
        verifyNoMoreInteractions(host);
    }
}
