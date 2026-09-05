package top.focess.veto.agent.tool;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.llm.core.ToolResultPresentationMode;

/**
 * Tests that ToolCallContext (agentId + userId) is threaded through tool execution, enabling
 * GroupTools to record the caller's identity instead of placeholders.
 */
class ToolCallContextTest {

    @Test
    void callStateIsIsolatedAcrossPlatformAndVirtualThreads() throws Exception {
        for (boolean virtual : new boolean[] {false, true}) {
            ToolCallContextHolder.setCurrentCallId("parent-call");
            ToolCallContextHolder.requestRewind(1, "parent-rewind");
            ToolCallContextHolder.requestReverseTransform("parent-transform");
            try {
                FutureTask<Void> child =
                        new FutureTask<>(
                                () -> {
                                    assertNull(ToolCallContextHolder.get());
                                    assertNull(ToolCallContextHolder.currentCallId());
                                    assertTrue(ToolCallContextHolder.drainPendingTurns().isEmpty());
                                    assertNull(ToolCallContextHolder.drainTransform());
                                    try {
                                        ToolCallContextHolder.setCurrentCallId("child-call");
                                        ToolCallContextHolder.requestRewind(1, "child-rewind");
                                        ToolCallContextHolder.requestReverseTransform(
                                                "child-transform");
                                    } finally {
                                        ToolCallContextHolder.clear();
                                    }
                                    assertNull(ToolCallContextHolder.currentCallId());
                                    assertTrue(ToolCallContextHolder.drainPendingTurns().isEmpty());
                                    assertNull(ToolCallContextHolder.drainTransform());
                                    return null;
                                });
                if (virtual) {
                    Thread.startVirtualThread(child);
                } else {
                    new Thread(child).start();
                }
                child.get(5, TimeUnit.SECONDS);
                assertEquals("parent-call", ToolCallContextHolder.currentCallId());
                assertEquals(1, ToolCallContextHolder.drainPendingTurns().size());
                assertEquals(
                        new ToolCallContextHolder.TransformRequest.ToStandalone("parent-transform"),
                        ToolCallContextHolder.drainTransform());
            } finally {
                ToolCallContextHolder.clear();
            }
        }
    }

    @Test
    void contextCapturesAgentIdAndUserId() {
        String agentId = "agent-123";
        UUID userId = UUID.fromString("12345678-1234-1234-1234-123456789abc");

        ToolCallContext ctx =
                new ToolCallContext(
                        agentId,
                        userId,
                        null,
                        null,
                        null,
                        ToolResultPresentationMode.BASIC,
                        false,
                        ToolExecutionPermit.empty());

        assertEquals(agentId, ctx.agentId(), "agentId should be captured");
        assertEquals(userId, ctx.userId(), "userId should be captured");
    }

    @Test
    void contextIsThreadLocalAccessible() {
        String agentId = "agent-thread";
        UUID userId = UUID.randomUUID();

        // Set in thread-local
        ToolCallContextHolder.set(
                new ToolCallContext(
                        agentId,
                        userId,
                        null,
                        null,
                        null,
                        ToolResultPresentationMode.BASIC,
                        false,
                        ToolExecutionPermit.empty()));

        // Read from same thread
        ToolCallContext ctx = ToolCallContextHolder.get();
        if (ctx == null) throw new AssertionError("Context should be available in ThreadLocal");
        assertEquals(agentId, ctx.agentId(), "agentId should match");
        assertEquals(userId, ctx.userId(), "userId should match");

        // Clear
        ToolCallContextHolder.clear();
        assertNull(ToolCallContextHolder.get(), "Context should be null after clear");
    }
}
