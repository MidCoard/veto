package top.focess.veto.agent.loop;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import top.focess.veto.plugin.runtime.PluginClassLoader;

class PromptLibraryTest {
    @Test
    void installedToolResourcesAreResolvedFromTheirPrivateLoader(@TempDir @NonNull Path directory)
            throws Exception {
        var jar = directory.resolve("tool.jar");
        var ownerName = PromptResourceOwner.class.getName();
        var classPath = ownerName.replace('.', '/') + ".class";
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry(classPath));
            output.write(new ClassPathResource(classPath).getContentAsByteArray());
            output.closeEntry();
            output.putNextEntry(new JarEntry("prompts/"));
            output.closeEntry();
            output.putNextEntry(new JarEntry("prompts/private-tool.mdc"));
            output.write(
                    "---\nversion: 2\nid: private-tool\nrequires: [name]\n---\nPrivate tool: {{name}}"
                            .getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        assertThrows(IllegalArgumentException.class, () -> PromptLibrary.source("private-tool"));
        try (var loader = new PluginClassLoader("fixture", List.of(jar.toUri().toURL()))) {
            var owner = loader.loadClass(ownerName);
            assertNotSame(PromptResourceOwner.class, owner);
            var result = PromptLibrary.compileTool(owner, "private-tool", Map.of("name", "Veto"));
            assertEquals("Private tool: Veto", result.text().strip());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> PromptLibrary.compileTool(owner, "default-system-prompt", Map.of()));
        }
    }

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

class PromptResourceOwner {}
