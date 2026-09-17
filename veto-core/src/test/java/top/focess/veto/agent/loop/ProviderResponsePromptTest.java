package top.focess.veto.agent.loop;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ProviderResponsePromptTest {
    @Test
    void semanticPlanJudgmentRequestsPlainTextWithoutAnEnvelope() {
        String prompt =
                PromptLibrary.text("runtime-judgment", Map.of("prompt", "Is this complete?"));
        assertTrue(prompt.contains("Return exactly true or false as plain text"));
        assertFalse(prompt.contains("in message"));
    }

    @Test
    void responseInstructionsFollowRuntimeCapabilities() {
        for (String entry : new String[] {"provider-native", "provider-anthropic"}) {
            for (boolean enabled : new boolean[] {false, true}) {
                String prompt =
                        PromptLibrary.compile(
                                        entry, Map.of("system", "System", "nativeCalls", enabled))
                                .text();
                assertFalse(prompt.contains("VetoResponse"));
                assertFalse(prompt.contains("response schema"));
                assertTrue(prompt.contains("ordinary text or Markdown"));
                assertEquals(enabled, prompt.contains("Invoke registered tools"));
                assertEquals(!enabled, prompt.contains("Tool execution is disabled"));
                assertFalse(prompt.contains("@if"));
            }
        }
    }
}
