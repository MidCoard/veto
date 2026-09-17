package top.focess.veto.agent.loop;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class PromptLibraryTest {
    @Test
    void nestedCustomFoldersResolveIncludesByStableId() {
        var result = PromptLibrary.compile("fixture-custom-entry", Map.of("name", "Veto"));
        assertEquals("Hello, Veto.", result.text().strip());
        assertEquals(1, result.messages().size());
        assertEquals("system", result.messages().getFirst().role());
        assertTrue(
                result.sources().stream()
                        .anyMatch(span -> span.source().equals("fixture-custom-shared.mdc")));
    }

    @Test
    void movedEntryAndNestedRuntimeSourcesKeepTheirLookupIds() throws Exception {
        assertEquals(
                new ClassPathResource("prompts/system/default-system-prompt.mdc")
                        .getContentAsString(StandardCharsets.UTF_8),
                PromptLibrary.source("default-system-prompt"));
        assertEquals(
                new ClassPathResource("prompts/runtime/compaction/runtime-compaction.mdc")
                        .getContentAsString(StandardCharsets.UTF_8),
                PromptLibrary.source("runtime-compaction"));
    }
}
