package top.focess.veto.agent.tool;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.api.llm.ToolResultPresentationMode;

/** Trusted caller and control state remain isolated to the executing thread. */
class ToolCallContextTest {

    @Test
    void callStateIsIsolatedAcrossPlatformAndVirtualThreads() throws Exception {
        for (boolean virtual : new boolean[] {false, true}) {
            ToolCallContextHolder.setCurrentCallId("parent-call");
            ToolCallContextHolder.finish("parent-result");
            try {
                FutureTask<Void> child =
                        new FutureTask<>(
                                () -> {
                                    assertNull(ToolCallContextHolder.get());
                                    assertNull(ToolCallContextHolder.currentCallId());
                                    assertNull(ToolCallContextHolder.drainResponse());
                                    try {
                                        ToolCallContextHolder.setCurrentCallId("child-call");
                                        ToolCallContextHolder.finish("child-result");
                                    } finally {
                                        ToolCallContextHolder.clear();
                                    }
                                    assertNull(ToolCallContextHolder.currentCallId());
                                    assertNull(ToolCallContextHolder.drainResponse());
                                    return null;
                                });
                if (virtual) {
                    Thread.startVirtualThread(child);
                } else {
                    new Thread(child).start();
                }
                child.get(5, TimeUnit.SECONDS);
                assertEquals("parent-call", ToolCallContextHolder.currentCallId());
                if (!(ToolCallContextHolder.drainResponse()
                        instanceof ToolCallContextHolder.ResponseDirective.Finish result))
                    throw new AssertionError("Expected finish response");
                assertEquals("parent-result", result.response().message());
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
                        ToolResultPresentationMode.BASIC,
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
                        ToolResultPresentationMode.BASIC,
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
