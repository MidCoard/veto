package top.focess.veto.agent.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.agent.mcp.ToolCallContext;
import top.focess.veto.agent.mcp.ToolCallContextHolder;
import top.focess.veto.agent.mcp.ToolDocs;
import top.focess.veto.agent.mcp.ToolExecutionException;
import top.focess.veto.agent.screening.DeployerPolicy;
import top.focess.veto.llm.core.ToolResultPresentationMode;

class PathToolsTest {

    private final @NonNull ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void clearContext() {
        ToolCallContextHolder.clear();
    }

    @Test
    void findFilesMatchesRootAndNestedFiles(@TempDir @NonNull Path root) throws Exception {
        Files.writeString(root.resolve("Root.java"), "class Root {}");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/Nested.java"), "class Nested {}");
        Files.writeString(root.resolve("src/readme.txt"), "text");
        permit("find_files", root, Map.of("absolutePath", root.toString()));

        JsonNode result =
                mapper.readTree(
                        new FindFilesTool()
                                .execute(
                                        new FindFilesTool.Args(
                                                root.toString(), "**/*.java", null)));

        assertEquals(2, result.get("matches").size());
        assertEquals("Root.java", result.get("matches").get(0).asText());
        assertEquals("src/Nested.java", result.get("matches").get(1).asText());
        assertFalse(result.get("truncated").asBoolean());
    }

    @Test
    void movePathDoesNotOverwrite(@TempDir @NonNull Path root) throws Exception {
        Path source = Files.writeString(root.resolve("source.txt"), "source");
        Path destination = Files.writeString(root.resolve("destination.txt"), "destination");
        permit(
                "move_path",
                root,
                Map.of(
                        "sourceAbsolutePath", source.toString(),
                        "destinationAbsolutePath", destination.toString()));

        ToolExecutionException error =
                assertThrows(
                        ToolDocs.nonNullClass(ToolExecutionException.class),
                        () ->
                                new MovePathTool()
                                        .execute(
                                                new MovePathTool.Args(
                                                        source.toString(),
                                                        destination.toString())));

        assertEquals("DESTINATION_EXISTS", error.errorCode());
        assertTrue(Files.exists(source));
        assertEquals("destination", Files.readString(destination));
    }

    @Test
    void deletePathRequiresRecursiveIntent(@TempDir @NonNull Path root) throws Exception {
        Path directory = Files.createDirectory(root.resolve("tree"));
        Files.writeString(directory.resolve("child.txt"), "child");
        permit("delete_path", root, Map.of("absolutePath", directory.toString()));

        ToolExecutionException error =
                assertThrows(
                        ToolDocs.nonNullClass(ToolExecutionException.class),
                        () ->
                                new DeletePathTool()
                                        .execute(
                                                new DeletePathTool.Args(
                                                        directory.toString(), false)));
        assertEquals("DIRECTORY_NOT_EMPTY", error.errorCode());

        JsonNode deleted =
                mapper.readTree(
                        new DeletePathTool()
                                .execute(new DeletePathTool.Args(directory.toString(), true)));
        assertEquals(2, deleted.get("entriesDeleted").asInt());
        assertFalse(Files.exists(directory));
    }

    private static void permit(
            @NonNull String toolName,
            @NonNull Path root,
            @NonNull Map<@NonNull String, @NonNull String> paths) {
        Map<String, ToolExecutionPermit.AuthorizedPath> authorized =
                new java.util.LinkedHashMap<>();
        paths.forEach(
                (name, path) ->
                        authorized.put(
                                name,
                                new ToolExecutionPermit.AuthorizedPath(
                                        name, path, Path.of(path), 0, true)));
        ToolExecutionPermit permit =
                new ToolExecutionPermit(
                        toolName,
                        Map.copyOf(paths),
                        authorized,
                        List.of(root),
                        root,
                        DeployerPolicy.FULL_ACCESS,
                        Set.of());
        ToolCallContextHolder.set(
                new ToolCallContext(
                        "agent",
                        UUID.randomUUID(),
                        null,
                        null,
                        UUID.randomUUID(),
                        ToolResultPresentationMode.BASIC,
                        permit));
    }
}
