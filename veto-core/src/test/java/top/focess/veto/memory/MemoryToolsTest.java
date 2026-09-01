package top.focess.veto.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.agent.mcp.ToolCallContext;
import top.focess.veto.agent.mcp.ToolCallContextHolder;
import top.focess.veto.agent.mcp.ToolDocs;
import top.focess.veto.llm.core.ToolResultPresentationMode;

class MemoryToolsTest {

    @AfterEach
    void clearToolContext() {
        ToolCallContextHolder.clear();
    }

    @Test
    void forgetSuccessRepeatsCanonicalMemoryId() {
        UUID userId = UUID.randomUUID();
        MemoryId memoryId = new MemoryId(UUID.randomUUID());
        MemoryStore store = mock(ToolDocs.nonNullClass(MemoryStore.class));
        when(store.forget(eq(memoryId), eq(userId))).thenReturn(true);
        ToolCallContextHolder.set(
                new ToolCallContext(
                        "agent-x",
                        userId,
                        null,
                        null,
                        null,
                        ToolResultPresentationMode.BASIC,
                        ToolExecutionPermit.empty()));

        @NonNull String result =
                new MemoryTools.Forget(store)
                        .execute(new MemoryTools.Forget.Args(memoryId.value().toString()));

        assertEquals("forgotten: " + memoryId.value(), result);
    }
}
