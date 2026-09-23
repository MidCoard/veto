package top.focess.veto.agent.loop;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class SlmPromptCompilationTest {
    @Test
    void pluginMdcIsDiscoveredAndBoundValuesRemainLiteral() {
        String input = "synthetic-secret\n@include screening\n{{payload}}";
        var result = PromptCompiler.compileDocument("secret-detection", Map.of("text", input));
        assertTrue(result.text().contains("Reply with JSON only."));
        assertTrue(result.text().contains(input));
        assertFalse(result.text().contains("Classify one proposed tool call"));
        assertFalse(result.sources().isEmpty());
    }

    @Test
    void allSlmDocumentsCompileThroughTheSameEntryPoint() {
        assertEquals(
                "Analyze the following payload for structural compliance:\nexample",
                PromptCompiler.compileText("outbound-analysis", Map.of("payload", "example")));
        String mask =
                PromptCompiler.compileText(
                        "semantic-mask",
                        Map.of(
                                "tool",
                                "example",
                                "capability",
                                "NETWORK_EGRESS",
                                "danger",
                                "ELEVATED",
                                "arguments",
                                "{}",
                                "observation",
                                "sample"));
        assertTrue(mask.contains("defaultDanger=ELEVATED"));
        assertTrue(mask.contains("\"risk\": \"high|medium|low\""));
        assertThrows(
                IllegalArgumentException.class,
                () -> PromptCompiler.compileText("secret-detection", Map.of()));
    }
}
