package top.focess.veto.agent.intercept;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.bus.SessionInvalidations;

class HitlCancellationRaceTest {
    @Test
    @Timeout(10)
    void approvalRegisteredAfterCancellationCannotParkTheExecutionThread() throws Exception {
        var invalidations = mock(SessionInvalidations.class);
        var registry = new HitlRegistry(null, invalidations);
        var entering = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        var exited = new CompletableFuture<Void>();
        var execution =
                Thread.ofPlatform()
                        .daemon(true)
                        .start(
                                () -> {
                                    try {
                                        entering.countDown();
                                        try {
                                            cancelled.await();
                                        } catch (InterruptedException interrupted) {
                                            Thread.currentThread().interrupt();
                                        }
                                        var pending =
                                                registry.register(
                                                        "agent",
                                                        "late",
                                                        new ToolCall("fixture", Map.of()),
                                                        null,
                                                        List.of(VetoOption.EXEC_DECLINE),
                                                        null);
                                        assertThrows(
                                                CancellationException.class,
                                                () -> registry.await("agent", "late"));
                                        assertTrue(Thread.currentThread().isInterrupted());
                                        assertTrue(pending.isCancelled());
                                        assertFalse(
                                                registry.resolveOption(
                                                        "agent", "late", "EXEC_DECLINE"));
                                        if (!exited.complete(null))
                                            throw new AssertionError("duplicate completion");
                                    } catch (Throwable failure) {
                                        if (!exited.completeExceptionally(failure))
                                            throw new AssertionError("duplicate failure");
                                    }
                                });
        try {
            assertTrue(entering.await(2, TimeUnit.SECONDS));
            assertEquals(0, registry.declineAll("agent"));
            execution.interrupt();
            cancelled.countDown();
            exited.get(5, TimeUnit.SECONDS);
            verify(invalidations, atLeast(2)).agentChanged("agent", "interactions");
        } finally {
            cancelled.countDown();
            execution.interrupt();
            registry.declineAll("agent");
        }
    }
}
