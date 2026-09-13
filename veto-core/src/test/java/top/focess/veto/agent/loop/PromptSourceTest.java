package top.focess.veto.agent.loop;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.identity.SystemPromptResolver;

class PromptSourceTest {
    private static final String HEADER =
            "---\nversion: 1\nid: test\nrequires: [VALUE, enabled]\n---\n";

    @Test
    void valuesAreNotRecursivelyInterpretedAndLocationsCoverFinalText() {
        var result =
                PromptSource.compile(
                        "test.mdc",
                        HEADER + "@if enabled\n@include shared\n@endif",
                        Map.of("VALUE", "{{VALUE}} @include private"),
                        Map.of("enabled", true),
                        Map.of("shared", "{{VALUE}}"));
        assertEquals("{{VALUE}} @include private\n", result.text());
        var span = result.sources().getFirst();
        assertEquals("shared", span.source());
        assertEquals(1, span.line());
        assertEquals(result.text(), result.text().substring(span.start(), span.end()));
        assertEquals(
                "",
                PromptSource.compile(
                                "test.mdc",
                                HEADER + "@if enabled\n{{VALUE}}\n@endif",
                                Map.of("VALUE", "secret-free"),
                                Map.of("enabled", false),
                                Map.of())
                        .text());
    }

    @Test
    void rejectsMissingVariablesSyntaxCyclesAndBlankRequiredBlocksWithoutEchoingValues() {
        for (String body :
                new String[] {
                    "{{MISSING}}",
                    "{{VALUE + 1}}",
                    "@if enabled",
                    "@include unknown",
                    "@execute anything",
                    "@if enabled\n@block identity priority=100\n@endif\n@end",
                    "@block identity priority=100 required\n@end"
                }) {
            var failure =
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    PromptSource.compile(
                                            "test.mdc",
                                            HEADER + body,
                                            Map.of("VALUE", "do-not-echo"),
                                            Map.of("enabled", true),
                                            Map.of()));
            assertTrue(String.valueOf(failure.getMessage()).contains("test.mdc:"));
            assertFalse(String.valueOf(failure.getMessage()).contains("do-not-echo"));
        }
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        PromptSource.compile(
                                "test.mdc",
                                HEADER + "@include shared",
                                Map.of("VALUE", "x"),
                                Map.of("enabled", true),
                                Map.of("shared", "@include shared")));
    }

    @Test
    void standardTemplatePreservesLegacyContentForBothModes() {
        var resolver = new SystemPromptResolver();
        Map<String, String> blocks = new HashMap<>(resolver.commonBlocks());
        for (String key :
                new String[] {
                    "LAW",
                    "IDENTITY",
                    "ROLE",
                    "DELEGATION_RULES",
                    "WORKSPACE",
                    "ENVIRONMENT",
                    "BOUNDARIES",
                    "SKILLS",
                    "RESULT_CONVENTIONS",
                    "TOOLS",
                    "GUIDED_PROTOCOL"
                }) blocks.put(key, "Block " + key);
        for (boolean guided : new boolean[] {false, true}) {
            blocks.put("GUIDED_PROTOCOL", guided ? resolver.guidedPrompt() : "");
            var result = resolver.compileStandard(blocks, guided);
            assertEquals(
                    PromptTemplate.render(resolver.defaultPrompt(), blocks),
                    result.text().replaceAll("\\n{3,}", "\n\n").strip());
            assertTrue(
                    result.sources().stream()
                            .anyMatch(span -> span.source().equals("common-answer")));
        }
    }
}
