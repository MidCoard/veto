package top.focess.veto.agent.intercept;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.screening.Relevance;
import top.focess.veto.agent.screening.Screening;
import top.focess.veto.api.agent.screening.Danger;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.plugin.contract.WorkflowHook;

class WorkflowApprovalTest {
    @Test
    void hooksCanRequireApprovalForAnInternalCallOrRejectIt() {
        var hitl = new HitlRegistry();
        var call = new ToolCall("internal", Map.of());
        var prompt =
                assertInstanceOf(
                        ToolDocs.nonNullClass(ApprovalDecision.Prompt.class),
                        hitl.decide(
                                "agent",
                                call,
                                null,
                                new GatewayResult.NotScreened(),
                                WorkflowHook.Decision.REQUIRE_APPROVAL));
        assertEquals(
                List.of(VetoOption.ACCEPT_GENERIC, VetoOption.GENERIC_DECLINE), prompt.options());
        assertInstanceOf(
                ToolDocs.nonNullClass(ApprovalDecision.Refused.class),
                hitl.decide(
                        "agent",
                        call,
                        null,
                        new GatewayResult.NotScreened(),
                        WorkflowHook.Decision.REJECT));
        assertEquals(
                ApprovalDecision.AUTO_APPROVE,
                hitl.decide(
                        "agent",
                        call,
                        null,
                        new GatewayResult.NotScreened(),
                        WorkflowHook.Decision.CONTINUE));
    }

    @Test
    void approvalCannotOverrideHostRefusal() {
        var hitl = new HitlRegistry();
        var critical =
                new GatewayResult.Screened(
                        new Screening(
                                Relevance.HIGH,
                                Danger.CRITICAL,
                                false,
                                VetoScenario.GENERIC,
                                "blocked"));
        assertInstanceOf(
                ToolDocs.nonNullClass(ApprovalDecision.Refused.class),
                hitl.decide(
                        "agent",
                        new ToolCall("dangerous", Map.of()),
                        null,
                        critical,
                        WorkflowHook.Decision.REQUIRE_APPROVAL));
    }
}
