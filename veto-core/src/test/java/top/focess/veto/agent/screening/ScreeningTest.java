package top.focess.veto.agent.screening;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.intercept.VetoScenario;
import top.focess.veto.agent.tool.AgentToolDefinition;
import top.focess.veto.agent.tool.ToolCapability;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.llm.core.ToolCall;

class ScreeningTest {

    @Test
    void unavailableProviderReturnsNoFabricatedJudgment() {
        SlmScreeningProvider provider = SlmScreeningProvider.unavailable();
        ToolCall call = new ToolCall("any", Map.of());
        assertTrue(provider.screen(call, anAgentToolDef(), "any thought").isEmpty());
    }

    @Test
    void screeningCarriesRelevanceDangerScenarioReason() {
        Screening s =
                new Screening(
                        Relevance.HIGH,
                        Danger.ELEVATED,
                        true,
                        VetoScenario.GENERIC,
                        "project write");
        assertEquals(Relevance.HIGH, s.relevance());
        assertEquals(Danger.ELEVATED, s.danger());
        assertTrue(s.slmEvaluated());
        assertEquals(VetoScenario.GENERIC, s.scenario());
        assertEquals("project write", s.reason());
    }

    private static @NonNull AgentToolDefinition anAgentToolDef() {
        return new AgentToolDefinition(
                "t",
                "d",
                ToolCapability.AGENT_CONTROL,
                Danger.SAFE,
                ToolDocs.nonNullClass(Object.class),
                Map.of());
    }
}
