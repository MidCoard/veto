package top.focess.veto.builtin.group;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import top.focess.veto.api.agent.AgentResult;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.plugin.agent.AgentHost;

@Timeout(10)
class MateAgentLifecycleTest {
    private void dispatch(
            @NonNull Blackboard board,
            @NonNull UUID group,
            @NonNull String node,
            @NonNull String attempt) {
        board.post(
                new BlackboardMessage(
                        node,
                        group,
                        "LEADER",
                        "mate",
                        BlackboardMessage.MessageType.TASK_DISPATCH,
                        node + ":work",
                        0,
                        attempt));
    }

    private void awaitMessages(@NonNull Blackboard board, @NonNull UUID group, int count)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (board.readAll(group).size() < count && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(count, board.readAll(group).size());
    }

    @Test
    void dispatchedResultStillRequiresAgentExitConfirmation() throws Exception {
        UUID id = UUID.randomUUID();
        var board = new Blackboard();
        var child = GroupTestHost.child("mate");
        var request = mock(ToolDocs.nonNullClass(AgentHost.Request.class));
        var result = CompletableFuture.completedFuture(AgentResult.success("report", Map.of()));
        when(request.result()).thenReturn(result);
        when(request.cancel(any())).thenReturn(false, true);
        when(child.submit(anyString())).thenReturn(request);
        var mate = new MateAgent("mate", id, "review", child, board, 5, 10);
        dispatch(board, id, "node", "attempt");
        mate.start();
        try {
            awaitMessages(board, id, 2);
            assertFalse(mate.cancelDispatch("attempt", Duration.ofSeconds(2)));
            assertTrue(mate.cancelDispatch("attempt", Duration.ofSeconds(2)));
            verify(request, times(2)).cancel(any());
            verify(child, never()).close();
        } finally {
            mate.stop();
        }
    }

    @Test
    void cancellationBeforeDispatchPreventsSubmission() throws Exception {
        UUID id = UUID.randomUUID();
        var board = new Blackboard();
        var child = GroupTestHost.child("mate");
        var mate = new MateAgent("mate", id, "review", child, board, 5, 10);
        assertTrue(mate.cancelDispatch("attempt", Duration.ZERO));
        dispatch(board, id, "node", "attempt");
        mate.start();
        try {
            Thread.sleep(50);
            verify(child, never()).submit(anyString());
        } finally {
            mate.stop();
        }
    }

    @Test
    void pollingTimeoutDoesNotFailLongRunningTask() throws Exception {
        UUID id = UUID.randomUUID();
        var board = new Blackboard();
        var child = GroupTestHost.child("mate");
        var request = mock(ToolDocs.nonNullClass(AgentHost.Request.class));
        var result = new CompletableFuture<AgentResult>();
        var submitted = new CountDownLatch(1);
        when(request.result()).thenReturn(result);
        when(child.submit(anyString()))
                .thenAnswer(
                        call -> {
                            submitted.countDown();
                            return request;
                        });
        var mate = new MateAgent("mate", id, "coding", child, board, 5, 10);
        dispatch(board, id, "node", "attempt-one");
        mate.start();
        try {
            assertTrue(submitted.await(2, TimeUnit.SECONDS));
            Thread.sleep(60);
            assertEquals(1, board.readAll(id).size(), "polling timeout must not post failure");
            result.complete(AgentResult.success("actual delayed report", Map.of()));
            awaitMessages(board, id, 2);
            assertEquals(BlackboardMessage.MessageType.ACCEPT, board.readAll(id).get(1).type());
            assertEquals("attempt-one", board.readAll(id).get(1).dispatchId());
            verify(child, times(1)).submit("work");
        } finally {
            mate.stop();
        }
    }

    @Test
    void stoppingInterruptsWaitAndDoesNotStartQueuedDispatchOrPostFailure() throws Exception {
        UUID id = UUID.randomUUID();
        var board = new Blackboard();
        var child = GroupTestHost.child("mate");
        var request = mock(ToolDocs.nonNullClass(AgentHost.Request.class));
        var submitted = new CountDownLatch(1);
        when(request.result()).thenReturn(new CompletableFuture<>());
        when(child.submit(anyString()))
                .thenAnswer(
                        call -> {
                            submitted.countDown();
                            return request;
                        });
        when(child.awaitTermination(any())).thenReturn(false, true);
        var mate = new MateAgent("mate", id, "coding", child, board, 5, 60_000);
        for (String node : List.of("one", "two")) dispatch(board, id, node, node);
        mate.start();
        try {
            assertTrue(submitted.await(2, TimeUnit.SECONDS));
            assertTimeout(Duration.ofSeconds(3), mate::stop);
            assertEquals(2, board.readAll(id).size());
            verify(child, times(1)).submit("work");
            verify(child, times(1)).close();
            assertFalse(
                    mate.awaitTermination(Duration.ofMillis(100)),
                    "waiter exit is not execution settlement");
            assertTrue(mate.awaitTermination(Duration.ofMillis(100)));
        } finally {
            mate.stop();
        }
    }
}
