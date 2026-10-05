package top.focess.veto.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import top.focess.veto.agent.capability.CapabilityResolver;
import top.focess.veto.agent.capability.ProtectedWorkspaceReadCapabilityImpl;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.agent.screening.DeployerPolicy;
import top.focess.veto.agent.screening.ProtectedSet;
import top.focess.veto.agent.workspace.PathMode;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.api.agent.capability.WorkspaceReadCapability;
import top.focess.veto.api.agent.capability.WorkspaceWriteCapability;
import top.focess.veto.api.agent.tool.NativeTool;
import top.focess.veto.api.agent.tool.ToolErrorCode;
import top.focess.veto.api.agent.tool.ToolExecutionException;
import top.focess.veto.api.event.BeforeTextCommitEvent;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.builtin.workspace.DeletePathTool;
import top.focess.veto.builtin.workspace.GrepSearchTool;
import top.focess.veto.builtin.workspace.ViewFileTool;
import top.focess.veto.builtin.workspace.WriteToFileTool;
import top.focess.veto.event.EventManager;
import top.focess.veto.integration.plugins.PluginTestSupport;

class WorkspaceFilePolicyTest {
    private static final @NonNull UUID USER = UUID.randomUUID();
    private static final @NonNull UUID SESSION = UUID.randomUUID();

    @Test
    void sandboxFileCapabilityCanReadUnclaimedSiblingButRejectsForeignClaim(
            @TempDir @NonNull Path root) throws Exception {
        Path base = root.toRealPath();
        Path selected = Files.createDirectory(base.resolve("selected"));
        Path sibling = Files.createDirectory(base.resolve("sibling"));
        Path file = Files.writeString(sibling.resolve("note.txt"), "sibling contents");
        var tool = new ViewFileTool();
        var permit =
                ToolExecutionPermit.capture(
                                new ToolCall(
                                        tool.getName(),
                                        Map.of("absolutePath", file.toString()),
                                        "resource-call"),
                                ToolSchemaCompiler.compileNative(tool),
                                Workspace.single(selected, PathMode.REAL),
                                DeployerPolicy.SANDBOXED,
                                ProtectedSet.empty())
                        .withAccessScope(List.of(base), List.of())
                        .withCaller("agent", USER, SESSION);
        ToolCallContextHolder.set(
                new ToolCallContext(
                        "agent", USER, SESSION, ToolResultPresentationMode.BASIC, permit));
        ToolCallContextHolder.setCurrentCallId(permit.callId());
        var capability = CapabilityResolver.require(WorkspaceReadCapability.class);
        try (var input = capability.file(file.toString()).openRead()) {
            assertEquals(
                    "sibling contents", new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        var occupied = permit.withAccessScope(List.of(base), List.of(sibling));
        ToolCallContextHolder.set(
                new ToolCallContext(
                        "agent", USER, SESSION, ToolResultPresentationMode.BASIC, occupied));
        var occupiedCapability = CapabilityResolver.require(WorkspaceReadCapability.class);
        assertThrows(ToolExecutionException.class, () -> occupiedCapability.file(file.toString()));
    }

    @Test
    void grepStopsAtOversizedInputLineAndRetainsExplicitIncompleteEvidence(
            @TempDir @NonNull Path root) throws Exception {
        var file =
                Files.writeString(
                        root.resolve("huge.txt"),
                        "needle\r\n" + "x".repeat(1_000_001) + "needle\n");
        var tool = new GrepSearchTool();
        bind(tool, Map.of("absolutePath", file.toString(), "query", "needle"), root, Set.of());
        String result =
                tool.execute(
                        new GrepSearchTool.Args(file.toString(), "needle", null, null),
                        CapabilityResolver.require(WorkspaceReadCapability.class));
        assertTrue(result.contains(":1: needle\n"), result);
        assertTrue(
                result.contains(
                        "[truncated: line limit 1000000 chars; remaining input was not searched]"),
                result);
        assertFalse(result.contains(":2:"));
    }

    @Test
    void grepDiscardsEarlierMatchesWhenALaterLineIsNotUtf8(@TempDir @NonNull Path root)
            throws Exception {
        var file = root.resolve("invalid.txt");
        byte[] prefix = ("needle\n" + "safe\n".repeat(2000)).getBytes(StandardCharsets.UTF_8);
        byte[] bytes = Arrays.copyOf(prefix, prefix.length + 1);
        bytes[prefix.length] = (byte) 0xff;
        Files.write(file, bytes);
        var tool = new GrepSearchTool();
        bind(tool, Map.of("absolutePath", file.toString(), "query", "needle"), root, Set.of());
        assertEquals(
                "(no matches)",
                tool.execute(
                        new GrepSearchTool.Args(file.toString(), "needle", null, null),
                        CapabilityResolver.require(WorkspaceReadCapability.class)));
    }

    @Test
    void protectedCaptureUsesSharedEventDeliveryAndHonorsPrevention(@TempDir @NonNull Path root) {
        var events = Mockito.mock(EventManager.class);
        Mockito.doAnswer(
                        invocation -> {
                            Object submitted = invocation.getArgument(0);
                            if (!(submitted instanceof BeforeTextCommitEvent event))
                                throw new AssertionError("Expected file-capture workflow event");
                            assertEquals(BeforeTextCommitEvent.Phase.FILE_CAPTURE, event.phase());
                            assertEquals("synthetic captured text", event.text());
                            event.cancel();
                            event.prevent();
                            return null;
                        })
                .when(events)
                .submit(Mockito.any());
        var tool = new ViewFileTool();
        bind(tool, Map.of("absolutePath", root.resolve("file.txt").toString()), root, Set.of());
        var capability = new ProtectedWorkspaceReadCapabilityImpl(events);
        assertThrows(
                IllegalStateException.class,
                () -> capability.captureFileText("synthetic captured text"));
        Mockito.verify(events).submit(Mockito.any(BeforeTextCommitEvent.class));
    }

    @Test
    void viewFileCapturesBeforeLineRendering(@TempDir @NonNull Path root) throws Exception {
        String key =
                "-----BEGIN PRIVATE KEY-----\r\nsynthetic-material\r\n-----END PRIVATE KEY-----";
        Path file =
                Files.writeString(
                        root.resolve("credential.txt"), "first\r\n" + key + "\r\nlast\r\n");
        try (var plugins = PluginTestSupport.manager()) {
            var tool =
                    new ViewFileTool(
                            new ProtectedWorkspaceReadCapabilityImpl(
                                    PluginTestSupport.eventManager(plugins)));
            bind(tool, Map.of("absolutePath", file.toString()), root, Set.of());
            var capability = CapabilityResolver.require(WorkspaceReadCapability.class);
            String result =
                    tool.execute(new ViewFileTool.Args(file.toString(), null, null), capability);
            assertTrue(result.startsWith("1: first\n2: [SECRET_REF:s_"), result);
            assertTrue(result.endsWith("3: \n4: \n5: last\n"), result);
            assertFalse(result.contains("synthetic-material"));
            String reference = result.substring(result.indexOf("s_"), result.indexOf(']'));
            assertEquals(
                    key,
                    PluginTestSupport.reveal(
                                    plugins,
                                    new Scope.AgentScope(USER, SESSION.toString(), "agent"),
                                    reference)
                            .orElseThrow());
            assertEquals("first\r\n" + key + "\r\nlast\r\n", Files.readString(file));
        }
    }

    @AfterEach
    void clearContext() {
        ToolCallContextHolder.clear();
    }

    @Test
    void lookupRefusesProtectedAndUnapprovedResources(@TempDir @NonNull Path root)
            throws Exception {
        Path workspace = root.toRealPath();
        Path file = Files.writeString(workspace.resolve("secret.txt"), "secret");
        bind(new ViewFileTool(), Map.of("absolutePath", file.toString()), workspace, Set.of(file));
        var workspaceCapability = CapabilityResolver.require(WorkspaceReadCapability.class);
        var refusal =
                assertThrows(
                        ToolExecutionException.class,
                        () -> workspaceCapability.file(file.toString()));
        assertEquals(ToolErrorCode.POLICY.PATH_PROTECTED, refusal.errorCode());
        assertThrows(
                SecurityException.class,
                () -> workspaceCapability.file(workspace.resolve("unapproved.txt").toString()));
        assertEquals("secret", Files.readString(file));
    }

    @Test
    void writableDirectoryLookupRefusesProtectedDescendantsBeforeTraversal(
            @TempDir @NonNull Path root) throws Exception {
        root = root.toRealPath();
        Path directory = Files.createDirectory(root.resolve("tree"));
        Path ordinary = Files.writeString(directory.resolve("ordinary.txt"), "keep");
        Path secret = Files.writeString(directory.resolve("secret.txt"), "secret");
        bind(
                new DeletePathTool(),
                Map.of("absolutePath", directory.toString(), "recursive", true),
                root,
                Set.of(secret));
        var workspace = CapabilityResolver.require(WorkspaceWriteCapability.class);
        var refusal =
                assertThrows(
                        ToolExecutionException.class, () -> workspace.file(directory.toString()));
        assertEquals(ToolErrorCode.POLICY.DESCENDANT_REFUSED, refusal.errorCode());
        assertEquals("keep", Files.readString(ordinary));
        assertEquals("secret", Files.readString(secret));
    }

    @Test
    void readOpenRejectsReplacementAfterLookup(@TempDir @NonNull Path root) throws Exception {
        Path file = Files.writeString(root.resolve("target.txt"), "approved");
        bind(new ViewFileTool(), Map.of("absolutePath", file.toString()), root, Set.of());
        var handle =
                CapabilityResolver.require(WorkspaceReadCapability.class).file(file.toString());
        Files.move(file, root.resolve("original.txt"));
        Files.writeString(file, "replacement");
        var failure = assertThrows(ToolExecutionException.class, handle::openRead);
        assertEquals(ToolErrorCode.WORKSPACE.TREE_CHANGED, failure.errorCode());
        assertEquals("replacement", Files.readString(file));
    }

    @Test
    void outputCloseCannotReplaceAFileSwappedAfterOpen(@TempDir @NonNull Path root)
            throws Exception {
        Path file = Files.writeString(root.resolve("target.txt"), "approved");
        bindWrite(file, root);
        var handle =
                CapabilityResolver.require(WorkspaceWriteCapability.class).file(file.toString());
        var output = handle.openForReplace();
        output.write("new content".getBytes(StandardCharsets.UTF_8));
        Path original = root.resolve("original.txt");
        Files.move(file, original);
        Files.writeString(file, "replacement");
        var failure = assertThrows(ToolExecutionException.class, output::close);
        assertEquals(ToolErrorCode.WORKSPACE.TREE_CHANGED, failure.errorCode());
        assertEquals("replacement", Files.readString(file));
        assertEquals("approved", Files.readString(original));
    }

    @Test
    void oversizedOutputNeverPublishesBufferedPrefix(@TempDir @NonNull Path root) throws Exception {
        Path file = root.resolve("oversized.txt");
        bindWrite(file, root);
        var handle =
                CapabilityResolver.require(WorkspaceWriteCapability.class).file(file.toString());
        try (var output = handle.openForCreate()) {
            byte[] block = new byte[1024 * 1024];
            for (int index = 0; index < 16; index++) {
                output.write(block);
            }
            assertThrows(IOException.class, () -> output.write(1));
        }
        assertFalse(Files.exists(file));
    }

    @Test
    void failedWriteNeverPublishesBufferedPrefix(@TempDir @NonNull Path root) throws Exception {
        Path file = root.resolve("failed.txt");
        bindWrite(file, root);
        var handle =
                CapabilityResolver.require(WorkspaceWriteCapability.class).file(file.toString());
        try (var output = handle.openForCreate()) {
            output.write("prefix".getBytes(StandardCharsets.UTF_8));
            assertThrows(IndexOutOfBoundsException.class, () -> output.write(new byte[1], 0, 2));
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
                        .withCaller("agent", USER, SESSION);
        ToolCallContextHolder.set(
                new ToolCallContext(
                        "agent", USER, SESSION, ToolResultPresentationMode.BASIC, permit));
        ToolCallContextHolder.setCurrentCallId(permit.callId());
    }
}
