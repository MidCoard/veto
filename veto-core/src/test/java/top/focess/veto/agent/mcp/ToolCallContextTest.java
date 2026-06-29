package top.focess.veto.agent.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Tests that ToolCallContext (agentId + userId) is threaded through tool execution, enabling
 * GroupTools to record the caller's identity instead of placeholders.
 */
class ToolCallContextTest {

    @Test
    void contextCapturesAgentIdAndUserId() {
        String agentId = "agent-123";
        UUID userId = UUID.fromString("12345678-1234-1234-1234-123456789abc");

        ToolCallContext ctx = new ToolCallContext(agentId, userId);

        assertEquals(agentId, ctx.agentId(), "agentId should be captured");
        assertEquals(userId, ctx.userId(), "userId should be captured");
    }

    @Test
    void contextIsThreadLocalAccessible() {
        String agentId = "agent-thread";
        UUID userId = UUID.randomUUID();

        // Set in thread-local
        ToolCallContextHolder.set(agentId, userId);

        // Read from same thread
        ToolCallContext ctx = ToolCallContextHolder.get();
        assertNotNull(ctx, "Context should be available in ThreadLocal");
        assertEquals(agentId, ctx.agentId(), "agentId should match");
        assertEquals(userId, ctx.userId(), "userId should match");

        // Clear
        ToolCallContextHolder.clear();
        assertNull(ToolCallContextHolder.get(), "Context should be null after clear");
    }
}
