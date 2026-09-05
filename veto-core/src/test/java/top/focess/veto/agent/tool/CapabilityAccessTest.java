package top.focess.veto.agent.tool;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.focess.veto.agent.capability.CapabilityAccess;
import top.focess.veto.agent.capability.CapabilityResolver;
import top.focess.veto.agent.capability.WorkspaceReadCapability;
import top.focess.veto.agent.capability.WorkspaceWriteCapability;
import top.focess.veto.agent.capability.WritableWorkspaceFile;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.agent.tool.builtin.ViewFileTool;
import top.focess.veto.agent.tool.builtin.WriteToFileTool;
import top.focess.veto.agent.workspace.PathMode;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.llm.core.ToolCall;
import top.focess.veto.llm.core.ToolResultPresentationMode;

class CapabilityAccessTest {
    private static final @NonNull UUID USER = UUID.randomUUID();

    @AfterEach
    void clearContext() {
        ToolCallContextHolder.clear();
    }

    private static @NonNull ToolExecutionPermit capture(
            @NonNull NativeTool<?> tool,
            @NonNull Map<String, Object> arguments,
            @NonNull Path root) {
        return ToolExecutionPermit.capture(
                        new ToolCall(tool.getName(), arguments, "screened-call"),
                        ToolSchemaCompiler.compileNative(tool),
                        Workspace.single(root, PathMode.REAL))
                .withCaller("agent", USER, null, "owner", null);
    }

    private static void bind(@NonNull ToolExecutionPermit permit, @NonNull String agentId) {
        ToolCallContextHolder.set(
                new ToolCallContext(
                        agentId,
                        USER,
                        null,
                        "owner",
                        null,
                        ToolResultPresentationMode.BASIC,
                        false,
                        permit));
        ToolCallContextHolder.setCurrentCallId(permit.callId());
    }

    @Test
    void authorityRejectsWrongCapabilityCallerAndCall(@TempDir @NonNull Path root) {
        var permit = capture(new ViewFileTool(), Map.of("absolutePath", root.toString()), root);
        bind(permit, "agent");
        CapabilityAccess.require(ToolCapability.WORKSPACE_READ, "view_file");
        assertThrows(
                SecurityException.class,
                () -> CapabilityAccess.require(ToolCapability.WORKSPACE_WRITE));
        assertThrows(
                SecurityException.class,
                () -> CapabilityAccess.require(ToolCapability.WORKSPACE_READ, "list_dir"));
        bind(permit, "another-agent");
        assertThrows(
                SecurityException.class,
                () -> CapabilityAccess.require(ToolCapability.WORKSPACE_READ));
        bind(permit, "agent");
        ToolCallContextHolder.setCurrentCallId("another-call");
        assertThrows(
                SecurityException.class,
                () -> CapabilityAccess.require(ToolCapability.WORKSPACE_READ));
    }

    @Test
    void lookupRestrictsResourcesAndReadHandlesCannotWrite(@TempDir @NonNull Path root)
            throws Exception {
        Path file = Files.writeString(root.resolve("allowed.txt"), "approved");
        bind(capture(new ViewFileTool(), Map.of("absolutePath", file.toString()), root), "agent");
        var workspace =
                CapabilityResolver.require(ToolDocs.nonNullClass(WorkspaceReadCapability.class));
        var handle = workspace.file(file.toString());
        assertFalse(handle instanceof WritableWorkspaceFile);
        try (var input = handle.openRead()) {
            assertEquals("approved", new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertThrows(
                SecurityException.class,
                () -> workspace.file(root.resolve("other.txt").toString()));
    }

    @Test
    void handlesAndOpenStreamsExpireWithInvocation(@TempDir @NonNull Path root) throws Exception {
        Path file = Files.writeString(root.resolve("read.txt"), "approved");
        var permit = capture(new ViewFileTool(), Map.of("absolutePath", file.toString()), root);
        bind(permit, "agent");
        var workspace =
                CapabilityResolver.require(ToolDocs.nonNullClass(WorkspaceReadCapability.class));
        var handle = workspace.file(file.toString());
        var input = handle.openRead();
        ToolCallContextHolder.clear();
        try {
            assertThrows(SecurityException.class, () -> input.read());
        } finally {
            input.close();
        }
        assertThrows(SecurityException.class, () -> handle.openRead());
        bind(capture(new ViewFileTool(), Map.of("absolutePath", file.toString()), root), "agent");
        assertThrows(SecurityException.class, () -> handle.openRead());
    }

    @Test
    void createDoesNotOverwriteAndReplacePublishesAtClose(@TempDir @NonNull Path root)
            throws Exception {
        Path file = root.resolve("written.txt");
        var tool = new WriteToFileTool();
        bind(
                capture(
                        tool,
                        Map.of(
                                "absolutePath",
                                file.toString(),
                                "codeContent",
                                "first",
                                "overwrite",
                                false),
                        root),
                "agent");
        var workspace =
                CapabilityResolver.require(ToolDocs.nonNullClass(WorkspaceWriteCapability.class));
        var handle = workspace.file(file.toString());
        try (var out = handle.openForCreate()) {
            out.write("first".getBytes(StandardCharsets.UTF_8));
        }
        assertEquals("first", Files.readString(file));
        bind(
                capture(
                        tool,
                        Map.of(
                                "absolutePath",
                                file.toString(),
                                "codeContent",
                                "second",
                                "overwrite",
                                true),
                        root),
                "agent");
        var replacement =
                CapabilityResolver.require(ToolDocs.nonNullClass(WorkspaceWriteCapability.class))
                        .file(file.toString());
        assertThrows(FileAlreadyExistsException.class, () -> replacement.openForCreate());
        try (var out = replacement.openForReplace()) {
            out.write("second".getBytes(StandardCharsets.UTF_8));
            assertEquals("first", Files.readString(file));
        }
        assertEquals("second", Files.readString(file));
    }

    @Test
    void expiredWriteStreamCannotCommit(@TempDir @NonNull Path root) throws Exception {
        Path file = root.resolve("uncommitted.txt");
        bind(
                capture(
                        new WriteToFileTool(),
                        Map.of(
                                "absolutePath",
                                file.toString(),
                                "codeContent",
                                "x",
                                "overwrite",
                                false),
                        root),
                "agent");
        var fileHandle =
                CapabilityResolver.require(ToolDocs.nonNullClass(WorkspaceWriteCapability.class))
                        .file(file.toString());
        var output = fileHandle.openForCreate();
        output.write(120);
        ToolCallContextHolder.clear();
        assertThrows(SecurityException.class, () -> output.close());
        assertFalse(Files.exists(file));
    }
}
