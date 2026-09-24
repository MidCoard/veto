package top.focess.veto.builtin.questions;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.contract.FrontendContribution.Scope;

class QuestionLifecycleTest {
    @Test
    void identitiesIncludeOwnerSessionAgentAndCall() {
        var runtime = new QuestionRuntime(null);
        var original = new PluginHost.Invocation("owner", "session", "agent", "request", "call");
        var pending = runtime.register(original, List.of());
        for (var scope :
                List.of(
                        new Scope("other", "session", "agent"),
                        new Scope("owner", "other", "agent"),
                        new Scope("owner", "session", "other"))) {
            assertTrue(runtime.pendingFor(scope).isEmpty());
            assertFalse(runtime.answer(scope, "call", Map.of()));
            assertFalse(runtime.cancel(scope, "call"));
        }
        assertFalse(pending.isDone());
        assertTrue(runtime.cancel(new Scope("owner", "session", "agent"), "call"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner", "session", "agent", "plugin"})
    void lifecycleCancelsOnlyMatchingBatches(@NonNull String transition) {
        var runtime = new QuestionRuntime(null);
        var target = runtime.register(QuestionTestSupport.invocation("agent", "call"), List.of());
        var other =
                runtime.register(
                        new PluginHost.Invocation("other", "other-session", "agent", null, "call"),
                        List.of());
        switch (transition) {
            case "owner" -> runtime.onOwnerClosed("owner");
            case "session" -> runtime.onSessionClosed("owner", "session");
            case "agent" -> runtime.onAgentTerminated("owner", "session", "agent");
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
                var runtime = new QuestionRuntime(null);
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
        @NonNull PluginHost host = mock();
        var runtime = new QuestionRuntime(host);
        var pending = runtime.register(QuestionTestSupport.invocation("agent", "call"), List.of());
        verify(host).invalidate("session", "interactions");
        clearInvocations(host);
        assertFalse(runtime.answer(QuestionTestSupport.scope("agent"), "missing", Map.of()));
        verifyNoInteractions(host);
        pending.completeExceptionally(new IllegalStateException("interrupted"));
        assertTrue(runtime.pendingFor(QuestionTestSupport.scope("agent")).isEmpty());
        verify(host).invalidate("session", "interactions");
    }
}
