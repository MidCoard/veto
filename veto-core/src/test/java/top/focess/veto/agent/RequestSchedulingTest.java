package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import top.focess.veto.agent.ExecutionControl.Wait;
import top.focess.veto.agent.drift.ReadHistory;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.intercept.Gateway;
import top.focess.veto.agent.intercept.HitlRegistry;
import top.focess.veto.agent.intercept.IngressDefense;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.tool.ToolEngine;
import top.focess.veto.api.agent.AgentAction;
import top.focess.veto.api.agent.AgentResult;
import top.focess.veto.api.agent.AgentState;
import top.focess.veto.api.llm.LlmBinding;
import top.focess.veto.api.llm.LlmOptions;
import top.focess.veto.api.llm.ProviderType;
import top.focess.veto.api.llm.VetoResponse;

class RequestSchedulingTest {
    @ParameterizedTest
    @EnumSource(Wait.class)
    void completionReleasesTransientWaitsButKeepsContinuationBarriers(@NonNull Wait reason) {
        var runner = runner();
        var action = new AgentAction.UserPromptAction("task");
        var request = runner.startTask(null, action);
        assertTrue(runner.beginRequest(new QueuedRequest(action, request)));
        runner.beginWait(reason);
        runner.complete(AgentResult.failure("failed", Map.of()));
        assertTrue(request.result().isDone());
        assertFalse(request.settled().isDone(), "Result delivery is not execution settlement");
        runner.finishTurn(request, false);
        assertTrue(request.settled().isDone());
        if (reason == Wait.BREAKER || reason == Wait.INTERRUPTED) {
            assertTrue(runner.control().waiting(reason));
        } else {
            assertEquals(AgentState.IDLE, runner.state());
            assertNull(runner.executionWaitReason());
        }
    }

    @Test
    void endingAnotherWaitCannotReleaseApproval() {
        var runner = runner();
        runner.beginWait(Wait.APPROVAL);
        runner.clearWait(Wait.QUESTION);
        runner.setActivity(ExecutionControl.Activity.MODEL);
        assertEquals(AgentState.INTERCEPTED, runner.state());
        runner.clearWait(Wait.APPROVAL);
        assertEquals(AgentState.IDLE, runner.state());
    }

    @Test
    void cancellationRejectsAnotherRunnersHandle() throws InterruptedException {
        var runner = runner();
        var foreign = runner().startTask(null, new AgentAction.UserPromptAction("foreign"));
        assertFalse(runner.cancelTask(foreign, Duration.ZERO));
        assertFalse(foreign.result().isDone());
    }

    private static @NonNull AgentRunner runner() {
        String id = UUID.randomUUID().toString();
        var gateway = mock(Gateway.class);
        when(gateway.readHistory()).thenReturn(new ReadHistory());
        var compiler = mock(PromptCompiler.class);
        var tools = mock(ToolEngine.class);
        return new AgentRunner(
                id,
                new AgentPersona(id, "Fixture", "Fixture", Set.of()),
                tools,
                new ToolExecutionBoundary(
                        id,
                        UUID.fromString(id),
                        null,
                        tools,
                        gateway,
                        new HitlRegistry(),
                        new IngressDefense()),
                List.of(),
                compiler,
                (request, session) -> new VetoResponse(null, null, "done"),
                new ObjectMapper(),
                50,
                new LlmBinding(ProviderType.ANTHROPIC, "test", "test", LlmOptions.defaults(), null),
                AgentEventSink.none(),
                UUID.randomUUID(),
                null);
    }
}
