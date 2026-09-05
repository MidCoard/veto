package top.focess.veto.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.focess.veto.agent.capability.CapabilityResolver;
import top.focess.veto.agent.capability.WorkspaceReadCapability;
import top.focess.veto.agent.capability.WorkspaceWriteCapability;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.agent.screening.DeployerPolicy;
import top.focess.veto.agent.screening.ProtectedSet;
import top.focess.veto.agent.tool.builtin.DeletePathTool;
import top.focess.veto.agent.tool.builtin.ViewFileTool;
import top.focess.veto.agent.tool.builtin.WriteToFileTool;
import top.focess.veto.agent.workspace.PathMode;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.llm.core.ToolCall;
import top.focess.veto.llm.core.ToolResultPresentationMode;

class WorkspaceFilePolicyTest {
    private static final @NonNull UUID USER = UUID.randomUUID();

    @AfterEach
    void clearContext() {
        ToolCallContextHolder.clear();
    }

    @Test
    void lookupRefusesProtectedAndUnapprovedResources(@TempDir @NonNull Path root)
            throws Exception {
        Path file = Files.writeString(root.resolve("secret.txt"), "secret");
        bind(new ViewFileTool(), Map.of("absolutePath", file.toString()), root, Set.of(file));
        var workspace =
                CapabilityResolver.require(ToolDocs.nonNullClass(WorkspaceReadCapability.class));
        var refusal =
                assertThrows(
                        ToolDocs.nonNullClass(ToolExecutionException.class),
                        () -> workspace.file(file.toString()));
        assertEquals("PATH_PROTECTED", refusal.errorCode());
        assertThrows(
                ToolDocs.nonNullClass(SecurityException.class),
                () -> workspace.file(root.resolve("unapproved.txt").toString()));
        assertEquals("secret", Files.readString(file));
    }

    @Test
    void writableDirectoryLookupRefusesProtectedDescendantsBeforeTraversal(
            @TempDir @NonNull Path root) throws Exception {
        Path directory = Files.createDirectory(root.resolve("tree"));
        Path ordinary = Files.writeString(directory.resolve("ordinary.txt"), "keep");
        Path secret = Files.writeString(directory.resolve("secret.txt"), "secret");
        bind(
                new DeletePathTool(),
                Map.of("absolutePath", directory.toString(), "recursive", true),
                root,
                Set.of(secret));
        var workspace =
                CapabilityResolver.require(ToolDocs.nonNullClass(WorkspaceWriteCapability.class));
        var refusal =
                assertThrows(
                        ToolDocs.nonNullClass(ToolExecutionException.class),
                        () -> workspace.file(directory.toString()));
        assertEquals("DESCENDANT_REFUSED", refusal.errorCode());
        assertEquals("keep", Files.readString(ordinary));
        assertEquals("secret", Files.readString(secret));
    }

    @Test
    void readOpenRejectsReplacementAfterLookup(@TempDir @NonNull Path root) throws Exception {
        Path file = Files.writeString(root.resolve("target.txt"), "approved");
        bind(new ViewFileTool(), Map.of("absolutePath", file.toString()), root, Set.of());
        var handle =
                CapabilityResolver.require(ToolDocs.nonNullClass(WorkspaceReadCapability.class))
                        .file(file.toString());
        Files.move(file, root.resolve("original.txt"));
        Files.writeString(file, "replacement");
        var failure =
                assertThrows(ToolDocs.nonNullClass(ToolExecutionException.class), handle::openRead);
        assertEquals("TREE_CHANGED", failure.errorCode());
        assertEquals("replacement", Files.readString(file));
    }

    @Test
    void outputCloseCannotReplaceAFileSwappedAfterOpen(@TempDir @NonNull Path root)
            throws Exception {
        Path file = Files.writeString(root.resolve("target.txt"), "approved");
        bindWrite(file, root);
        var handle =
                CapabilityResolver.require(ToolDocs.nonNullClass(WorkspaceWriteCapability.class))
                        .file(file.toString());
        var output = handle.openForReplace();
        output.write("new content".getBytes(StandardCharsets.UTF_8));
        Path original = root.resolve("original.txt");
        Files.move(file, original);
        Files.writeString(file, "replacement");
        var failure =
                assertThrows(ToolDocs.nonNullClass(ToolExecutionException.class), output::close);
        assertEquals("TREE_CHANGED", failure.errorCode());
        assertEquals("replacement", Files.readString(file));
        assertEquals("approved", Files.readString(original));
    }

    @Test
    void oversizedOutputNeverPublishesBufferedPrefix(@TempDir @NonNull Path root) throws Exception {
        Path file = root.resolve("oversized.txt");
        bindWrite(file, root);
        var handle =
                CapabilityResolver.require(ToolDocs.nonNullClass(WorkspaceWriteCapability.class))
                        .file(file.toString());
        try (var output = handle.openForCreate()) {
            byte[] block = new byte[1024 * 1024];
            for (int index = 0; index < 16; index++) {
                output.write(block);
            }
            assertThrows(ToolDocs.nonNullClass(IOException.class), () -> output.write(1));
        }
        assertFalse(Files.exists(file));
    }

    @Test
    void failedWriteNeverPublishesBufferedPrefix(@TempDir @NonNull Path root) throws Exception {
        Path file = root.resolve("failed.txt");
        bindWrite(file, root);
        var handle =
                CapabilityResolver.require(ToolDocs.nonNullClass(WorkspaceWriteCapability.class))
                        .file(file.toString());
        try (var output = handle.openForCreate()) {
            output.write("prefix".getBytes(StandardCharsets.UTF_8));
            assertThrows(
                    ToolDocs.nonNullClass(IndexOutOfBoundsException.class),
                    () -> output.write(new byte[1], 0, 2));
        }
        assertFalse(Files.exists(file));
    }

    private static void bindWrite(@NonNull Path file, @NonNull Path root) {
        bind(
                new WriteToFileTool(),
                Map.of(
                        "absolutePath",
                        file.toString(),
                        "codeContent",
                        "new content",
                        "overwrite",
                        true),
                root,
                Set.of());
    }

    private static void bind(
            @NonNull NativeTool<?> tool,
            @NonNull Map<String, Object> args,
            @NonNull Path root,
            @NonNull Set<Path> protectedPaths) {
        var permit =
                ToolExecutionPermit.capture(
                                new ToolCall(tool.getName(), args, "resource-call"),
                                ToolSchemaCompiler.compileNative(tool),
                                Workspace.single(root, PathMode.REAL),
                                DeployerPolicy.PROTECTED,
                                new ProtectedSet(protectedPaths))
                        .withCaller("agent", USER, null, "owner", null);
        ToolCallContextHolder.set(
                new ToolCallContext(
                        "agent",
                        USER,
                        null,
                        "owner",
                        null,
                        ToolResultPresentationMode.BASIC,
                        false,
                        permit));
        ToolCallContextHolder.setCurrentCallId(permit.callId());
    }
}
