package top.focess.veto.group;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import top.focess.veto.agent.Agent;
import top.focess.veto.agent.AgentResult;
import top.focess.veto.agent.tool.ToolDocs;

@Timeout(10)
class MateAgentLifecycleTest {
    @Test
    void dispatchedResultStillRequiresAgentExitConfirmation() throws Exception {
        UUID groupId = UUID.randomUUID();
        Blackboard board = new Blackboard();
        Agent agent = mock(ToolDocs.nonNullClass(Agent.class));
        var result = CompletableFuture.completedFuture(AgentResult.success("report", Map.of()));
        CountDownLatch awaited = new CountDownLatch(1);
        when(agent.result()).thenReturn(result);
        when(agent.await(any(ToolDocs.nonNullClass(Duration.class))))
                .thenAnswer(
                        invocation -> {
                            awaited.countDown();
                            return result.get();
                        });
        when(agent.cancelTask(eq(result), any(ToolDocs.nonNullClass(Duration.class))))
                .thenReturn(false, true);
        MateAgent mate =
                new MateAgent(
                        "mate",
                        groupId,
                        "review",
                        agent,
                        board,
                        new MateBreakerRegistry(),
                        50,
                        5,
                        10);
        board.post(
                new BlackboardMessage(
                        "dispatch",
                        groupId,
                        "LEADER",
                        "mate",
                        BlackboardMessage.MessageType.TASK_DISPATCH,
                        "node:work",
                        0,
                        "attempt"));
        mate.start();
        try {
            assertTrue(awaited.await(2, TimeUnit.SECONDS));
            assertFalse(mate.cancelDispatch("attempt", Duration.ofSeconds(2)));
            assertTrue(mate.cancelDispatch("attempt", Duration.ofSeconds(2)));
            verify(agent, times(2))
                    .cancelTask(eq(result), any(ToolDocs.nonNullClass(Duration.class)));
            verify(agent, never()).terminate();
        } finally {
            mate.stop();
        }
    }

    @Test
    void cancellationBeforeDispatchPreventsSubmission() throws Exception {
        UUID groupId = UUID.randomUUID();
        Blackboard board = new Blackboard();
        Agent agent = mock(ToolDocs.nonNullClass(Agent.class));
        MateAgent mate =
                new MateAgent(
                        "mate",
                        groupId,
                        "review",
                        agent,
                        board,
                        new MateBreakerRegistry(),
                        50,
                        5,
                        10);
        assertTrue(mate.cancelDispatch("attempt", Duration.ZERO));
        board.post(
                new BlackboardMessage(
                        "dispatch",
                        groupId,
                        "LEADER",
                        "mate",
                        BlackboardMessage.MessageType.TASK_DISPATCH,
                        "node:work",
                        0,
                        "attempt"));
        mate.start();
        try {
            Thread.sleep(50);
            verify(agent, never()).submit(anyString());
        } finally {
            mate.stop();
        }
    }

    @Test
    void pollingTimeoutDoesNotFailLongRunningTask() throws Exception {
        UUID groupId = UUID.randomUUID();
        Blackboard blackboard = new Blackboard();
        Agent agent = mock(ToolDocs.nonNullClass(Agent.class));
        CountDownLatch secondWait = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        when(agent.await(any(ToolDocs.nonNullClass(Duration.class))))
                .thenThrow(new TimeoutException())
                .thenAnswer(
                        invocation -> {
                            secondWait.countDown();
                            assertTrue(finish.await(2, TimeUnit.SECONDS));
                            return AgentResult.success("actual delayed report", Map.of());
                        });
        MateAgent mate =
                new MateAgent(
                        "mate",
                        groupId,
                        "coding",
                        agent,
                        blackboard,
                        new MateBreakerRegistry(),
                        50,
                        5,
                        10);
        blackboard.post(
                new BlackboardMessage(
                        "dispatch",
                        groupId,
                        "LEADER",
                        "mate",
                        BlackboardMessage.MessageType.TASK_DISPATCH,
                        "node:work",
                        0,
                        "attempt-one"));
        mate.start();
        try {
            assertTrue(secondWait.await(2, TimeUnit.SECONDS));
            assertEquals(1, blackboard.readAll(groupId).size(), "timeout must not post failure");
            finish.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (blackboard.readAll(groupId).size() == 1 && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertEquals(2, blackboard.readAll(groupId).size());
            assertEquals(
                    BlackboardMessage.MessageType.ACCEPT,
                    blackboard.readAll(groupId).get(1).type());
            assertEquals("attempt-one", blackboard.readAll(groupId).get(1).dispatchId());
            verify(agent, times(1)).submit("work");
        } finally {
            finish.countDown();
            mate.stop();
        }
    }

    @Test
    void stoppingInterruptsWaitAndDoesNotStartQueuedDispatchOrPostFailure() throws Exception {
        UUID groupId = UUID.randomUUID();
        Blackboard blackboard = new Blackboard();
        Agent agent = mock(ToolDocs.nonNullClass(Agent.class));
        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        when(agent.await(any(ToolDocs.nonNullClass(Duration.class))))
                .thenAnswer(
                        invocation -> {
                            waiting.countDown();
                            try {
                                new CountDownLatch(1).await();
                            } catch (InterruptedException e) {
                                interrupted.countDown();
                                throw e;
                            }
                            return AgentResult.success("unreachable", Map.of());
                        });
        MateAgent mate =
                new MateAgent(
                        "mate",
                        groupId,
                        "coding",
                        agent,
                        blackboard,
                        new MateBreakerRegistry(),
                        50,
                        5,
                        60_000);
        for (String node : List.of("one", "two")) {
            blackboard.post(
                    new BlackboardMessage(
                            node,
                            groupId,
                            "LEADER",
                            "mate",
                            BlackboardMessage.MessageType.TASK_DISPATCH,
                            node + ":work",
                            0));
        }
        mate.start();
        try {
            assertTrue(waiting.await(2, TimeUnit.SECONDS));
            assertTimeout(Duration.ofSeconds(3), mate::stop);
            assertEquals(0, interrupted.getCount());
            assertEquals(2, blackboard.readAll(groupId).size());
            verify(agent, times(1)).submit("work");
            verify(agent, times(1)).terminate();
            assertFalse(
                    mate.awaitTermination(Duration.ofMillis(20)),
                    "stopped waiter alone does not prove agent execution stopped");
            when(agent.awaitTermination(any(ToolDocs.nonNullClass(Duration.class))))
                    .thenReturn(true);
            assertTrue(mate.awaitTermination(Duration.ofMillis(20)));
        } finally {
            mate.stop();
        }
    }
}
