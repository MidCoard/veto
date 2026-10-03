package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.tool.RemoteToolDefinition;
import top.focess.veto.agent.tool.ToolEngine;
import top.focess.veto.api.agent.tool.ToolResultStatus;
import top.focess.veto.api.event.BeforeToolEvent;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.contract.JsonValue;

class AgentToolCancellationTest {
    @Test
    void cancelledDirectCallReturnsRefusalWithoutAuthorizationOrExecution() {
        var call = new ToolCall("fixture", Map.of());
        var engine = mock(ToolEngine.class);
        when(engine.resolveDefinition("fixture")).thenReturn(mock(RemoteToolDefinition.class));
        var boundary = mock(ToolExecutionBoundary.class);
        var output = mock(AgentOutput.class);
        var hooks = mock(AgentPluginHooks.class);
        var event =
                new BeforeToolEvent(
                        new Scope.AgentScope("owner", "session", "agent"),
                        new BeforeToolEvent.Invocation(
                                "fixture", call.callId(), new JsonValue.ObjectValue(Map.of())));
        event.cancel();
        when(hooks.beforeTool(call)).thenReturn(event);
        var execution =
                new AgentToolExecution(
                        engine,
                        boundary,
                        mock(ModelResponseValidation.class),
                        new ObjectMapper(),
                        List.of(),
                        output,
                        hooks,
                        mock(AgentRunner.class),
                        "agent",
                        UUID.randomUUID(),
                        "owner",
                        UUID.randomUUID());
        var invocation =
                new AgentToolExecution.Invocation(
                        new RequestHandle(new Object()),
                        ToolResultPresentationMode.BASIC,
                        Set.of("fixture"),
                        AgentExecutionPolicy.ordinary(),
                        0);

        var result = execution.executeOneCall(call, new ToolBatch(null, false, null), invocation);

        assertEquals(ToolResultStatus.REFUSED, result.status());
        assertFalse(result.success());
        assertTrue(result.content().startsWith("REFUSED"));
        verify(output).appendToolCall(call, null);
        verify(output).appendToolResponse(result);
        verifyNoInteractions(boundary);
        verify(engine).resolveDefinition("fixture");
        verifyNoMoreInteractions(engine);
    }
}
