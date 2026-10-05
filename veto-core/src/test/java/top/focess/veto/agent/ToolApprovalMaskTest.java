package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.drift.ReadHistory;
import top.focess.veto.agent.intercept.Gateway;
import top.focess.veto.agent.intercept.GatewayResult;
import top.focess.veto.agent.intercept.HitlRegistry;
import top.focess.veto.agent.intercept.IngressDefense;
import top.focess.veto.agent.intercept.VetoOption;
import top.focess.veto.agent.tool.RemoteToolDefinition;
import top.focess.veto.agent.tool.ToolEngine;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolResult;
import top.focess.veto.api.event.BeforeToolEvent;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.bus.SessionInvalidations;
import top.focess.veto.integration.plugins.PluginManager;
import top.focess.veto.vault.TestUsers;

class ToolApprovalMaskTest {
    @Test
    void readApprovalCarriesItsMaskChoiceThroughAuthorizationToTheRealDefense() {
        var plugins = mock(PluginManager.class);
        when(plugins.applyObservationMiddleware("sensitive contents")).thenReturn("redacted");
        var ingress = new IngressDefense(null, plugins);
        var hitl = new HitlRegistry(null, mock(SessionInvalidations.class));
        var gateway = mock(Gateway.class);
        when(gateway.readHistory()).thenReturn(new ReadHistory());
        when(gateway.screen(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new GatewayResult.NotScreened());
        var tools = mock(ToolEngine.class);
        var definition = mock(RemoteToolDefinition.class);
        when(definition.capability()).thenReturn(ToolCapability.WORKSPACE_READ);
        var boundary =
                new ToolExecutionBoundary(
                        "agent", UUID.randomUUID(), TestUsers.OWNER, tools, gateway, hitl, ingress);
        for (var option : List.of(VetoOption.ACCEPT_READ, VetoOption.ACCEPT_AND_MASK_READ)) {
            var call = new ToolCall("fixture", Map.of());
            var screened =
                    boundary.assess(
                            call,
                            definition,
                            "read file",
                            null,
                            null,
                            "request",
                            BeforeToolEvent.Decision.REQUIRE_APPROVAL);
            boundary.register(screened, List.of(option), null, null);
            assertTrue(hitl.resolveOption("agent", call.callId(), option.name()));
            boundary.await(call.callId());
            var authorized = boundary.authorize(screened);
            var result = ToolResult.success("fixture", call.callId(), "sensitive contents");
            assertEquals(
                    option == VetoOption.ACCEPT_READ ? "sensitive contents" : "redacted",
                    boundary.defend(authorized, result, null));
            assertThrows(SecurityException.class, () -> boundary.authorize(screened));
        }
        verify(plugins).applyObservationMiddleware("sensitive contents");
    }
}
