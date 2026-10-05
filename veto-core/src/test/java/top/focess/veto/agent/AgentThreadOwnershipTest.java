package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;
import static top.focess.veto.agent.AgentRunnerTest.binding;
import static top.focess.veto.agent.AgentRunnerTest.requireAgent;
import static top.focess.veto.agent.AgentRunnerTest.serviceWith;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import top.focess.veto.api.agent.AgentState;
import top.focess.veto.api.agent.workflow.PluginAwait;
import top.focess.veto.api.llm.VetoResponse;
import top.focess.veto.vault.TestUsers;

class AgentThreadOwnershipTest {
    @Test
    void stopRejectsResultWithoutReleasingWaitsOrClaimingExecutionSettlement() throws Exception {
        var calls = new AtomicInteger();
        var owner = new AtomicReference<AgentRunner>();
        var entered = new CountDownLatch(1);
        var release = new Semaphore(0);
        var signal = new CompletableFuture<Boolean>();
        var waitThread = new CompletableFuture<Thread>();
        var modelThread = new CompletableFuture<Thread>();
        var resultThread = new CompletableFuture<Thread>();
        var service =
                serviceWith(
                        (request, session) -> {
                            if (calls.incrementAndGet() == 2) {
                                var runner = owner.get();
                                if (runner == null) throw new AssertionError("Missing runner");
                                modelThread.complete(Thread.currentThread());
                                signal.whenComplete(
                                        (value, error) -> {
                                            assertFalse(Thread.holdsLock(runner));
                                            waitThread.complete(Thread.currentThread());
                                        });
                                runner.currentRequest()
                                        .await(
                                                new PluginAwait("pending", signal),
                                                runner::signalWork);
                                entered.countDown();
                                release.acquireUninterruptibly();
                            }
                            return new VetoResponse(null, null, "done");
                        });
        try {
            service.submit(
                    "thread-stop",
                    "warmup",
                    binding("System"),
                    Duration.ofSeconds(5),
                    TestUsers.OWNER);
            var agent = requireAgent(service.agent("thread-stop"));
            Object owned = ReflectionTestUtils.getField(agent, "runner");
            if (!(owned instanceof AgentRunner runner)) throw new AssertionError("Missing runner");
            owner.set(runner);
            var task =
                    agent.submitRequest(
                            "blocked",
                            result -> {
                                assertFalse(Thread.holdsLock(runner));
                                resultThread.complete(Thread.currentThread());
                            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var queued = agent.submitRequest("queued");
            agent.shutdown();
            assertEquals(AgentState.TERMINATED, agent.state());
            assertThrows(IllegalStateException.class, () -> agent.submitRequest("too late"));
            assertFalse(
                    signal.isDone(), "Stop rejects the result, but only the owner releases waits");
            assertTrue(task.result().isDone());
            assertFalse(task.await(Duration.ofSeconds(5)).success());
            assertFalse(task.settled().isDone());
            release.release();
            assertTrue(agent.awaitTermination(Duration.ofSeconds(5)));
            assertFalse(task.await(Duration.ofSeconds(5)).success());
            assertFalse(queued.await(Duration.ofSeconds(5)).success());
            assertTrue(task.settled().get(5, TimeUnit.SECONDS));
            assertTrue(queued.settled().get(5, TimeUnit.SECONDS));
            assertSame(modelThread.get(5, TimeUnit.SECONDS), waitThread.get(5, TimeUnit.SECONDS));
            assertSame(Thread.currentThread(), resultThread.get(5, TimeUnit.SECONDS));
            assertEquals(2, calls.get());
        } finally {
            release.release();
            service.remove("thread-stop");
        }
    }

    @Test
    void queuedCancellationCallbackCanSubmitWithoutHoldingTheAdmissionGate() throws Exception {
        var calls = new AtomicInteger();
        var entered = new CountDownLatch(1);
        var release = new Semaphore(0);
        var service =
                serviceWith(
                        (request, session) -> {
                            if (calls.incrementAndGet() == 2) {
                                entered.countDown();
                                release.acquireUninterruptibly();
                            }
                            assertFalse(Thread.currentThread().isInterrupted());
                            return new VetoResponse(null, null, "done");
                        });
        try {
            service.submit(
                    "thread-cancel",
                    "warmup",
                    binding("System"),
                    Duration.ofSeconds(5),
                    TestUsers.OWNER);
            var agent = requireAgent(service.agent("thread-cancel"));
            Object owned = ReflectionTestUtils.getField(agent, "runner");
            if (!(owned instanceof AgentRunner runner)) throw new AssertionError("Missing runner");
            var active = agent.submitRequest("active");
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var callback = new CompletableFuture<Boolean>();
            var following = new AtomicReference<RequestHandle>();
            var cancelled =
                    agent.submitRequest(
                            "cancel me",
                            result -> {
                                callback.complete(Thread.holdsLock(runner));
                                following.set(agent.submitRequest("following"));
                            });
            assertTrue(agent.cancelTask(cancelled, Duration.ofSeconds(1)));
            assertFalse(callback.get(5, TimeUnit.SECONDS));
            assertFalse(active.result().isDone());
            release.release();
            assertTrue(active.await(Duration.ofSeconds(5)).success());
            var next = following.get();
            if (next == null) throw new AssertionError("Callback did not submit");
            assertTrue(next.await(Duration.ofSeconds(5)).success());
            assertTrue(agent.cancelTask(cancelled, Duration.ofSeconds(1)));
            assertTrue(next.settled().get(5, TimeUnit.SECONDS));
            assertEquals(3, calls.get());
        } finally {
            release.release();
            service.remove("thread-cancel");
        }
    }
}
