package top.focess.veto.agent.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.agent.screening.DeployerPolicy;
import top.focess.veto.agent.tool.ToolCallContext;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.agent.tool.ToolCapability;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.agent.tool.ToolExecutionException;
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
                                .execute(new FindFilesTool.Args(root.toString(), "**/*.java")));

        assertEquals(2, result.get("matches").size());
        assertEquals("Root.java", result.get("matches").get(0).asText());
        assertEquals("src/Nested.java", result.get("matches").get(1).asText());
        assertFalse(result.get("truncated").asBoolean());
    }

    @Test
    void findFilesSkipsProtectedDescendants(@TempDir @NonNull Path root) throws Exception {
        Files.writeString(root.resolve("visible.txt"), "visible");
        Path protectedDirectory = Files.createDirectory(root.resolve("protected"));
        Files.writeString(protectedDirectory.resolve("secret.txt"), "secret");
        permit(
                "find_files",
                root,
                Map.of("absolutePath", root.toString()),
                Set.of(protectedDirectory));

        JsonNode result =
                mapper.readTree(
                        new FindFilesTool()
                                .execute(new FindFilesTool.Args(root.toString(), "**/*.txt")));

        assertEquals(1, result.get("matches").size());
        assertEquals("visible.txt", result.get("matches").get(0).asText());
        assertTrue(result.get("skippedEntries").asInt() >= 1);
    }

    @Test
    void movePathMovesDanglingLinkWithoutResolvingItsTarget(@TempDir @NonNull Path root)
            throws Exception {
        Path missingTarget = root.resolve("does-not-exist.txt");
        Path source = root.resolve("source-link");
        Path destination = root.resolve("destination-link");
        try {
            Files.createSymbolicLink(source, missingTarget);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            Assumptions.abort("Symbolic links unavailable: " + e.getMessage());
        }
        permit(
                "move_path",
                root,
                Map.of(
                        "sourceAbsolutePath", source.toString(),
                        "destinationAbsolutePath", destination.toString()));

        JsonNode moved =
                mapper.readTree(
                        new MovePathTool()
                                .execute(
                                        new MovePathTool.Args(
                                                source.toString(), destination.toString())));

        assertEquals("moved", moved.get("status").asText());
        assertEquals("symbolic_link", moved.get("kind").asText());
        assertFalse(Files.exists(source, LinkOption.NOFOLLOW_LINKS));
        assertEquals(missingTarget, Files.readSymbolicLink(destination));
        assertFalse(Files.exists(missingTarget));
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
    void writeAndReplaceUseTheAuthorizedTarget(@TempDir @NonNull Path root) throws Exception {
        Path file = root.resolve("created.txt");
        permit("write_to_file", root, Map.of("absolutePath", file.toString()));

        JsonNode written =
                mapper.readTree(
                        new WriteToFileTool()
                                .execute(
                                        new WriteToFileTool.Args(
                                                file.toString(), "first\nsecond\n", false)));

        assertEquals("ok", written.get("status").asText());
        assertEquals("first\nsecond\n", Files.readString(file));

        permit("replace_file_content", root, Map.of("absolutePath", file.toString()));
        JsonNode replaced =
                mapper.readTree(
                        new ReplaceFileContentTool()
                                .execute(
                                        new ReplaceFileContentTool.Args(
                                                file.toString(), 2, 2, "second", "updated")));

        assertEquals("ok", replaced.get("status").asText());
        assertEquals("first\nupdated\n", Files.readString(file));
    }

    @Test
    void writeRefusesAProtectedTarget(@TempDir @NonNull Path root) {
        Path protectedDirectory = root.resolve("protected");
        Path file = protectedDirectory.resolve("secret.txt");
        permit(
                "write_to_file",
                root,
                Map.of("absolutePath", file.toString()),
                Set.of(protectedDirectory));

        ToolExecutionException error =
                assertThrows(
                        ToolDocs.nonNullClass(ToolExecutionException.class),
                        () ->
                                new WriteToFileTool()
                                        .execute(
                                                new WriteToFileTool.Args(
                                                        file.toString(), "secret", false)));

        assertEquals("PATH_PROTECTED", error.errorCode());
        assertFalse(Files.exists(file));
    }

    @Test
    void workspaceWriteFailsClosedWithoutAnExecutionPermit(@TempDir @NonNull Path root) {
        Path file = root.resolve("blocked.txt");

        assertThrows(
                SecurityException.class,
                () ->
                        new WriteToFileTool()
                                .execute(
                                        new WriteToFileTool.Args(
                                                file.toString(), "blocked", false)));
        assertFalse(Files.exists(file));
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
        permit(toolName, root, paths, Set.of());
    }

    private static void permit(
            @NonNull String toolName,
            @NonNull Path root,
            @NonNull Map<@NonNull String, @NonNull String> paths,
            @NonNull Set<@NonNull Path> protectedPaths) {
        Map<String, ToolExecutionPermit.AuthorizedPath> authorized = new LinkedHashMap<>();
        paths.forEach(
                (name, path) ->
                        authorized.put(
                                name,
                                new ToolExecutionPermit.AuthorizedPath(
                                        name,
                                        path,
                                        Path.of(path),
                                        0,
                                        true,
                                        ToolExecutionPermit.FileIdentity.capture(Path.of(path)),
                                        ToolExecutionPermit.FileIdentity.capture(
                                                Path.of(path).getParent()))));
        ToolExecutionPermit permit =
                new ToolExecutionPermit(
                        toolName,
                        "test-call",
                        "find_files".equals(toolName)
                                ? ToolCapability.WORKSPACE_READ
                                : ToolCapability.WORKSPACE_WRITE,
                        null,
                        Map.copyOf(paths),
                        authorized,
                        List.of(root),
                        root,
                        DeployerPolicy.FULL_ACCESS,
                        protectedPaths,
                        null);
        ToolCallContextHolder.set(
                new ToolCallContext(
                        "agent",
                        UUID.randomUUID(),
                        null,
                        null,
                        UUID.randomUUID(),
                        ToolResultPresentationMode.BASIC,
                        false,
                        permit));
    }
}
