package top.focess.veto.agent.loop;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;

class PromptDocumentTest {
    private static @NonNull String source(@NonNull String inputs, @NonNull String body) {
        return "---\nversion: 2\nid: test\nrequires: [" + inputs + "]\n---\n" + body;
    }

    @Test
    void compilesBranchesListsMessagesAndIncludesWithoutInterpretingData() {
        var sources =
                Map.of(
                        "main",
                        source(
                                "enabled, items",
                                "@message user\n\n@if enabled\n@include list\n@else\nnone\n@endif\n@endmessage"),
                        "list",
                        source("items", "@for item in items\n{{loop.index}}: {{item}}\n@endfor"));
        var result =
                PromptDocument.compile(
                        "main",
                        Map.of("enabled", true, "items", List.of("@include evil", "{{secret}}")),
                        sources);
        assertEquals("0: @include evil\n1: {{secret}}", result.messages().getFirst().content());
        assertTrue(result.sources().stream().anyMatch(span -> span.source().equals("list.mdc")));
        for (var span : result.sources())
            assertTrue(span.start() >= 0 && span.end() <= result.text().length());
        assertEquals(
                "none",
                PromptDocument.compile(
                                "main", Map.of("enabled", false, "items", List.of()), sources)
                        .messages()
                        .getFirst()
                        .content());
    }

    @Test
    void requiresDeclaredInputsAndStrictTypes() {
        var sources = Map.of("main", source("enabled", "@if enabled\nyes\n@endif"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PromptDocument.compile("main", Map.of(), sources));
        assertThrows(
                IllegalArgumentException.class,
                () -> PromptDocument.compile("main", Map.of("enabled", "true"), sources));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        PromptDocument.compile(
                                "main",
                                Map.of("enabled", false),
                                Map.of(
                                        "main",
                                        source("enabled", "@if enabled\n{{secret}}\n@endif"))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        PromptDocument.compile(
                                "main",
                                Map.of("secret", "x"),
                                Map.of("main", source("", "{{secret}}"))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        PromptDocument.compile(
                                "main", Map.of(), Map.of("main", source("", "@include main"))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        PromptDocument.compile(
                                "main",
                                Map.of(),
                                Map.of("main", source("", "@message tool\nx\n@endmessage"))));
    }

    @Test
    void serializesMapsDeterministicallyAndPreservesArrayOrder() {
        Map<String, Object> forward = new LinkedHashMap<>();
        forward.put("z", List.of(2, 1));
        forward.put("a", Map.of("y", 2, "b", 1));
        Map<String, Object> reverse = new LinkedHashMap<>();
        reverse.put("a", Map.of("b", 1, "y", 2));
        reverse.put("z", List.of(2, 1));
        var sources = Map.of("main", source("value", "{{json(value)}}"));
        String first = PromptDocument.compile("main", Map.of("value", forward), sources).text();
        assertEquals(
                first, PromptDocument.compile("main", Map.of("value", reverse), sources).text());
        assertEquals("{\"a\":{\"b\":1,\"y\":2},\"z\":[2,1]}\n", first);
    }

    @Test
    void standardModesKeepSharedInstructionsAndExposeTheirActualSources() {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("persona", Map.of("name", "test", "description", "", "role", "STANDALONE"));
        inputs.put("workspace", Map.of("roots", List.of(), "pathMode", "REAL"));
        inputs.put("environment", Map.of("os", "Linux", "arch", "amd64", "windows", false));
        inputs.put("lawSources", List.of());
        inputs.put("guidance", "");
        inputs.put("policy", "PROTECTED");
        inputs.put("presentation", "BASIC");
        inputs.put("tools", List.of());
        inputs.put("toolNames", List.of("submit_plan"));
        inputs.put("skills", List.of());
        for (boolean guided : List.of(false, true)) {
            inputs.put("guided", guided);
            var result = PromptLibrary.compile("default-system-prompt", inputs);
            assertEquals(1, result.messages().size());
            assertTrue(result.text().contains(PromptLibrary.text("answer-style")));
            assertEquals(guided, result.text().contains(PromptLibrary.text("plan-system-prompt")));
            assertFalse(result.text().contains("## Your Tools"));
            assertTrue(
                    result.sources().stream()
                            .anyMatch(span -> span.source().equals("answer-style.mdc")));
        }
    }

    @Test
    void numericPredicatesHandleLongBudgetAndMessageSourcesMatchText() {
        var message =
                PromptLibrary.message(
                        "runtime-completion", Map.of("tool", "finish", "remaining", 1L));
        assertTrue(message.content().contains("final allowed model call"));
        assertFalse(message.content().contains("two model calls"));
        assertFalse(message.promptSources().isEmpty());
        for (var span : message.promptSources())
            assertTrue(span.start() >= 0 && span.end() <= message.content().length());
    }
}
