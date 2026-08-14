package top.focess.veto.agent.screening;

import static org.junit.jupiter.api.Assertions.*;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.intercept.VetoScenario;
import top.focess.veto.agent.mcp.AgentToolDefinition;
import top.focess.veto.agent.mcp.ToolDocs;
import top.focess.veto.llm.core.ToolCall;

class ScreeningTest {

    @Test
    void degradedProviderAlwaysReturnsHigh() {
        SlmRelevanceProvider provider = new DegradedSlmRelevanceProvider();
        ToolCall call = new ToolCall("any", java.util.Map.of());
        assertEquals(Relevance.HIGH, provider.relevance(call, anAgentToolDef(), "any thought"));
    }

    @Test
    void screeningCarriesRelevanceDangerScenarioReason() {
        Screening s =
                new Screening(
                        Relevance.HIGH, Danger.ELEVATED, VetoScenario.GENERIC, "project write");
        assertEquals(Relevance.HIGH, s.relevance());
        assertEquals(Danger.ELEVATED, s.danger());
        assertEquals(VetoScenario.GENERIC, s.scenario());
        assertEquals("project write", s.reason());
    }

    private static @NonNull AgentToolDefinition anAgentToolDef() {
        return new AgentToolDefinition(
                "t", "d", ToolDocs.nonNullClass(Object.class), java.util.Map.of());
    }
}
