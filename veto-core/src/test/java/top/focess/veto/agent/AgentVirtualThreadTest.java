package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;
import static top.focess.veto.agent.AgentRunnerTest.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.test.util.ReflectionTestUtils;
import top.focess.veto.api.llm.VetoResponse;
import top.focess.veto.vault.TestUsers;

class AgentVirtualThreadTest {
    @Test
    @Timeout(30)
    void manyIdleAgentsParkVirtualThreadsAndWakeForSubmittedWork() throws Exception {
        var calls = new AtomicInteger();
        var executionIsVirtual = new AtomicBoolean();
        var service =
                serviceWith(
                        (request, sessionId) -> {
                            calls.incrementAndGet();
                            executionIsVirtual.set(Thread.currentThread().isVirtual());
                            return new VetoResponse(null, null, "Completed");
                        });
        List<@NonNull String> keys = new ArrayList<>();
        List<@NonNull VetoAgent> agents = new ArrayList<>();
        List<@NonNull Thread> workers = new ArrayList<>();
        try {
            for (int i = 0; i < 64; i++) {
                var key = UUID.randomUUID().toString();
                keys.add(key);
                var registered =
                        service.getOrCreateAgent(
                                key, binding("System"), List.of(), TestUsers.OWNER);
                var agent = requireAgent(service.agent(key));
                assertSame(agent, registered);
                agents.add(agent);
                var value = ReflectionTestUtils.getField(agent, "executionThread");
                if (!(value instanceof Thread worker))
                    throw new AssertionError("Missing agent worker");
                assertTrue(worker.isVirtual());
                workers.add(worker);
            }
            awaitCondition(
                    () ->
                            workers.stream()
                                    .allMatch(worker -> worker.getState() == Thread.State.WAITING));
            assertEquals(0, calls.get(), "Idle agents must not poll the model");
            var request = agents.getFirst().submitRequest("Please respond");
            assertTrue(request.await(Duration.ofSeconds(5)).success());
            assertTrue(executionIsVirtual.get());
            assertEquals(1, calls.get());
        } finally {
            keys.forEach(service::remove);
            for (var agent : agents) assertTrue(agent.awaitTermination(Duration.ofSeconds(5)));
        }
        assertTrue(workers.stream().noneMatch(Thread::isAlive));
    }
}
