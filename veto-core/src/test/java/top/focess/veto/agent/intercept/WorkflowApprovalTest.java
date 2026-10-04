package top.focess.veto.agent.intercept;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import top.focess.veto.agent.screening.Relevance;
import top.focess.veto.agent.screening.Screening;
import top.focess.veto.api.agent.screening.Danger;
import top.focess.veto.api.event.BeforeToolEvent;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.bus.SessionInvalidations;

class WorkflowApprovalTest {
    @Test
    void hooksCanRequireApprovalForAnInternalCallOrRejectIt() {
        var hitl = new HitlRegistry(null, Mockito.mock(SessionInvalidations.class));
        var call = new ToolCall("internal", Map.of());
        var prompt =
                assertInstanceOf(
                        ApprovalDecision.Prompt.class,
                        hitl.decide(
                                "agent",
                                call,
                                null,
                                new GatewayResult.NotScreened(),
                                BeforeToolEvent.Decision.REQUIRE_APPROVAL));
        assertEquals(
                List.of(VetoOption.ACCEPT_GENERIC, VetoOption.GENERIC_DECLINE), prompt.options());
        assertInstanceOf(
                ApprovalDecision.Refused.class,
                hitl.decide(
                        "agent",
                        call,
                        null,
                        new GatewayResult.NotScreened(),
                        BeforeToolEvent.Decision.REJECT));
        assertEquals(
                ApprovalDecision.AUTO_APPROVE,
                hitl.decide(
                        "agent",
                        call,
                        null,
                        new GatewayResult.NotScreened(),
                        BeforeToolEvent.Decision.CONTINUE));
    }

    @Test
    void approvalCannotOverrideHostRefusal() {
        var hitl = new HitlRegistry(null, Mockito.mock(SessionInvalidations.class));
        var critical =
                new GatewayResult.Screened(
                        new Screening(
                                Relevance.HIGH,
                                Danger.CRITICAL,
                                false,
                                VetoScenario.GENERIC,
                                "blocked"));
        assertInstanceOf(
                ApprovalDecision.Refused.class,
                hitl.decide(
                        "agent",
                        new ToolCall("dangerous", Map.of()),
                        null,
                        critical,
                        BeforeToolEvent.Decision.REQUIRE_APPROVAL));
    }
}
