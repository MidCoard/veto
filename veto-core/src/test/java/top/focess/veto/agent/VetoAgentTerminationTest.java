package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.identity.Role;
import top.focess.veto.agent.tool.ToolDocs;

@Timeout(10)
class VetoAgentTerminationTest {
    @Test
    void terminalStateDoesNotConfirmExecutionExit() throws Exception {
        AgentRunner runner = mock(ToolDocs.nonNullClass(AgentRunner.class));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(
                        invocation -> {
                            entered.countDown();
                            release.await();
                            return null;
                        })
                .when(runner)
                .run();
        when(runner.state()).thenReturn(AgentState.TERMINATED);
        VetoAgent agent =
                new VetoAgent(
                        new AgentPersona(
                                "termination-test", "test", "test", Set.of(), List.of(), Role.MATE),
                        runner);
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            agent.terminate();
            assertEquals(AgentState.TERMINATED, agent.state());
            assertFalse(agent.awaitTermination(Duration.ofMillis(20)));
            release.countDown();
            assertTrue(agent.awaitTermination(Duration.ofSeconds(2)));
            assertTrue(agent.awaitTermination(Duration.ZERO));
        } finally {
            release.countDown();
            agent.terminate();
        }
    }
}
