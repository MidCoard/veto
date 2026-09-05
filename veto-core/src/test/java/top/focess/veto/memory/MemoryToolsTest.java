package top.focess.veto.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import top.focess.veto.agent.capability.MemoryReadCapabilityImpl;
import top.focess.veto.agent.capability.MemoryWriteCapabilityImpl;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.agent.tool.CapabilityTestCalls;
import top.focess.veto.agent.tool.ToolCallContext;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.llm.core.ToolResultPresentationMode;
import top.focess.veto.memory.embedder.Embedder;
import top.focess.veto.util.Nullness;

class MemoryToolsTest {

    @Test
    void directReadCapabilityCannotReachStoreWithoutAuthorization() {
        MemoryStore store = mock(ToolDocs.nonNullClass(MemoryStore.class));
        MemoryReadCapabilityImpl capability = new MemoryReadCapabilityImpl(store);
        ToolCallContextHolder.clear();
        assertThrows(
                ToolDocs.nonNullClass(SecurityException.class),
                () -> capability.recall(new MemoryTools.RecallMemory.Args("private memory")));
        verifyNoInteractions(store);
    }

    @AfterEach
    void clearToolContext() throws Exception {
        ToolCallContextHolder.clear();
    }

    @Test
    void forgetSuccessRepeatsCanonicalMemoryId() throws Exception {
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
                        false,
                        ToolExecutionPermit.empty()));

        MemoryTools.ForgetMemory tool =
                new MemoryTools.ForgetMemory(
                        new MemoryWriteCapabilityImpl(
                                store, mock(ToolDocs.nonNullClass(Embedder.class))));
        @NonNull String result =
                CapabilityTestCalls.execute(
                        tool, new MemoryTools.ForgetMemory.Args(memoryId.value().toString()));

        assertEquals("forget_memory", tool.getName());
        assertEquals("forgotten: " + memoryId.value(), result);
    }

    @Test
    void recallMemorySearchesBothTiersAndRanksTheirCombinedResults() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        MemoryStore store = mock(ToolDocs.nonNullClass(MemoryStore.class));
        Memory sessionMemory =
                new Memory(
                        MemoryId.random(),
                        userId,
                        sessionId,
                        MemoryTier.SESSION,
                        null,
                        "session result",
                        new float[] {1.0f},
                        Memory.SourceRef.turnRange(1, 1),
                        Instant.now());
        Memory insight =
                new Memory(
                        MemoryId.random(),
                        userId,
                        null,
                        MemoryTier.CROSS_SESSION,
                        null,
                        "cross-session result",
                        new float[] {1.0f},
                        Memory.SourceRef.insightOrigin("test"),
                        Instant.now());
        when(store.search(any(ToolDocs.nonNullClass(MemoryQuery.class))))
                .thenAnswer(
                        invocation -> {
                            MemoryQuery query =
                                    Nullness.requireNonNull(
                                            invocation.getArgument(0),
                                            "Mockito must pass the captured memory query");
                            return query.tiers().contains(MemoryTier.SESSION)
                                    ? List.of(new MemoryStore.ScoredMemory(sessionMemory, 0.7f))
                                    : List.of(new MemoryStore.ScoredMemory(insight, 0.9f));
                        });
        ToolCallContextHolder.set(
                new ToolCallContext(
                        "agent-x",
                        userId,
                        null,
                        null,
                        sessionId,
                        ToolResultPresentationMode.BASIC,
                        false,
                        ToolExecutionPermit.empty()));

        MemoryTools.RecallMemory tool =
                new MemoryTools.RecallMemory(new MemoryReadCapabilityImpl(store));
        String result =
                CapabilityTestCalls.execute(
                        tool, new MemoryTools.RecallMemory.Args("authentication"));

        assertEquals("recall_memory", tool.getName());
        assertTrue(result.indexOf("cross-session result") < result.indexOf("session result"));
        ArgumentCaptor<MemoryQuery> queries =
                ArgumentCaptor.forClass(ToolDocs.nonNullClass(MemoryQuery.class));
        verify(store, times(2)).search(queries.capture());
        assertEquals(List.of(MemoryTier.SESSION), queries.getAllValues().get(0).tiers());
        assertEquals(sessionId, queries.getAllValues().get(0).sessionFilter());
        assertEquals(List.of(MemoryTier.CROSS_SESSION), queries.getAllValues().get(1).tiers());
        assertNull(queries.getAllValues().get(1).sessionFilter());
    }
}
