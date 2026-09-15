package top.focess.veto.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ProviderResponsePromptTest {
    @Test
    void responseInstructionsFollowRuntimeCapabilities() {
        for (String entry : new String[] {"provider-native", "provider-anthropic"}) {
            for (boolean nativeCalls : new boolean[] {false, true}) {
                for (boolean jsonCalls : new boolean[] {false, true}) {

                    for (boolean guide : new boolean[] {false, true}) {
                        String prompt =
                                PromptLibrary.compile(
                                                entry,
                                                Map.of(
                                                        "system", "System",
                                                        "schema", Map.of("type", "object"),
                                                        "nativeCalls", nativeCalls,
                                                        "jsonCalls", jsonCalls,
                                                        "guide", guide))
                                        .text();
                        assertTrue(
                                prompt.contains(
                                        "VetoResponse is the JSON text response format, not a tool"));
                        assertEquals(
                                nativeCalls, prompt.contains("without a VetoResponse wrapper"));
                        assertFalse(
                                prompt.contains(
                                        "Each JSON call has `tool_name` and `args` inside `calls`"));
                        assertFalse(prompt.contains("To invoke tools, return a VetoResponse"));
                        assertEquals(guide, prompt.contains("To submit a guided program"));
                        assertTrue(prompt.contains("For a final answer, return"));
                        assertFalse(prompt.contains("@if"));
                    }
                }
            }
        }
    }
}
