package top.focess.veto.agent.tool;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.focess.veto.agent.capability.CapabilityAccess;
import top.focess.veto.agent.capability.CapabilityResolver;
import top.focess.veto.agent.capability.LoopControlCapabilityImpl;
import top.focess.veto.agent.capability.SkillReadCapabilityImpl;
import top.focess.veto.agent.capability.UserInteractionCapabilityImpl;
import top.focess.veto.agent.capability.WorkspaceReadCapability;
import top.focess.veto.agent.capability.WorkspaceWriteCapability;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.agent.skills.SkillRegistry;
import top.focess.veto.agent.tool.builtin.AskUserTool;
import top.focess.veto.agent.tool.builtin.DeletePathTool;
import top.focess.veto.agent.tool.builtin.LoadSkillTool;
import top.focess.veto.agent.tool.builtin.ThinkTool;
import top.focess.veto.agent.tool.builtin.UserQuestionRegistry;
import top.focess.veto.agent.tool.builtin.ViewFileTool;
import top.focess.veto.agent.tool.builtin.WriteToFileTool;
import top.focess.veto.agent.workspace.PathMode;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.llm.core.ToolCall;
import top.focess.veto.llm.core.ToolResultPresentationMode;

class CapabilityAccessTest {
    private static final @NonNull UUID USER = UUID.randomUUID();

    @Test
    void noOpStillRequiresItsOwnAuthorizedInvocation(@TempDir @NonNull Path root) throws Exception {
        var capability = new LoopControlCapabilityImpl();
        var args = new ThinkTool.Args();
        assertThrows(SecurityException.class, () -> capability.continueLoop(args));
        var definition =
                AgentToolDefinition.from(
                        "think",
                        ToolDocs.nonNullClass(ThinkTool.Args.class),
                        ToolCapability.LOOP_CONTROL);
        var call = new ToolCall("think", Map.of(), "think-call");
        var permit =
                ToolExecutionPermit.capture(call, definition, Workspace.single(root, PathMode.REAL))
                        .withCaller("agent", USER, null, "owner", null);
        bind(permit, "agent");
        assertEquals("", capability.continueLoop(args));
        bind(permit, "other-agent");
        assertThrows(SecurityException.class, () -> capability.continueLoop(args));
        var otherDefinition =
                AgentToolDefinition.from(
                        "other",
                        ToolDocs.nonNullClass(ThinkTool.Args.class),
                        ToolCapability.LOOP_CONTROL);
        var otherPermit =
                ToolExecutionPermit.capture(
                                new ToolCall("other", Map.of(), "other-call"),
                                otherDefinition,
                                Workspace.single(root, PathMode.REAL))
                        .withCaller("agent", USER, null, "owner", null);
        bind(otherPermit, "agent");
        assertThrows(SecurityException.class, () -> capability.continueLoop(args));
    }

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
    void rejectsWrongCapabilityNameCallerCallIdAndArgumentsBeforeResourceAccess(
            @TempDir @NonNull Path root) throws Exception {
        Path file = root.resolve("evidence.txt");
        Files.writeString(file, "unchanged");
        var permit = capture(new ViewFileTool(), Map.of("absolutePath", file.toString()), root);
        var args = new ViewFileTool.Args(file.toString(), null, null);
        bind(permit, "agent");
        assertDoesNotThrow(
                () -> CapabilityAccess.require(ToolCapability.WORKSPACE_READ, "view_file", args));
        assertThrows(
                ToolDocs.nonNullClass(SecurityException.class),
                () -> CapabilityAccess.require(ToolCapability.WORKSPACE_WRITE, "view_file", args));
        assertThrows(
                ToolDocs.nonNullClass(SecurityException.class),
                () -> CapabilityAccess.require(ToolCapability.WORKSPACE_READ, "list_dir", args));
        assertThrows(
                ToolDocs.nonNullClass(SecurityException.class),
                () ->
                        CapabilityAccess.require(
                                ToolCapability.WORKSPACE_READ,
                                "view_file",
                                new ViewFileTool.Args(file.toString(), 2, null)));
        ToolCallContextHolder.setCurrentCallId("other-call");
        assertThrows(
                ToolDocs.nonNullClass(SecurityException.class),
                () -> CapabilityAccess.require(ToolCapability.WORKSPACE_READ, "view_file", args));
        bind(permit, "other-agent");
        assertThrows(
                ToolDocs.nonNullClass(SecurityException.class),
                () -> CapabilityAccess.require(ToolCapability.WORKSPACE_READ, "view_file", args));
        ToolCallContextHolder.clear();
        assertThrows(
                ToolDocs.nonNullClass(SecurityException.class),
                () -> CapabilityAccess.require(ToolCapability.WORKSPACE_READ, "view_file", args));
        assertEquals("unchanged", Files.readString(file));
    }

    @Test
    void readPermitCannotAcquireWriteAuthorityOrReuseEscapedReadAuthority(
            @TempDir @NonNull Path root) throws Exception {
        Path file = root.resolve("evidence.txt");
        Files.writeString(file, "original");
        var permit = capture(new ViewFileTool(), Map.of("absolutePath", file.toString()), root);
        bind(permit, "agent");
        var read = CapabilityResolver.require(ToolDocs.nonNullClass(WorkspaceReadCapability.class));
        assertTrue(read.readText("absolutePath", null, null).contains("original"));
        assertThrows(
                ToolDocs.nonNullClass(SecurityException.class),
                () ->
                        CapabilityResolver.require(
                                ToolDocs.nonNullClass(WorkspaceWriteCapability.class)));
        assertThrows(
                ToolDocs.nonNullClass(SecurityException.class),
                () -> read.listDirectory("absolutePath"));
        ToolCallContextHolder.clear();
        assertThrows(
                ToolDocs.nonNullClass(SecurityException.class),
                () -> read.readText("absolutePath", null, null));
        bind(capture(new ViewFileTool(), Map.of("absolutePath", file.toString()), root), "agent");
        assertThrows(
                ToolDocs.nonNullClass(SecurityException.class),
                () -> read.readText("absolutePath", null, null));
        assertEquals("original", Files.readString(file));
    }

    @Test
    void writePermitCannotBeSubstitutedForDeleteOrReusedByAnotherCall(@TempDir @NonNull Path root)
            throws Exception {
        Path file = root.resolve("preserve.txt");
        Files.writeString(file, "original");
        var arguments =
                Map.<String, Object>of(
                        "absolutePath",
                        file.toString(),
                        "codeContent",
                        "replacement",
                        "overwrite",
                        true);
        var permit = capture(new WriteToFileTool(), arguments, root);
        bind(permit, "agent");
        var write =
                CapabilityResolver.require(ToolDocs.nonNullClass(WorkspaceWriteCapability.class));
        assertThrows(
                ToolDocs.nonNullClass(SecurityException.class),
                () -> write.deletePath("absolutePath", false));
        bind(capture(new WriteToFileTool(), arguments, root), "agent");
        assertThrows(
                ToolDocs.nonNullClass(SecurityException.class),
                () -> write.writeText("absolutePath", "replacement", true));
        assertEquals("original", Files.readString(file));
    }

    @Test
    void missingPermitCannotLoadSkillsOrPublishQuestions() {
        ToolCallContextHolder.clear();
        var skills = mock(ToolDocs.nonNullClass(SkillRegistry.class));
        var questions = mock(ToolDocs.nonNullClass(UserQuestionRegistry.class));
        var skillCapability = new SkillReadCapabilityImpl(skills);
        var questionCapability = new UserInteractionCapabilityImpl(questions);
        assertThrows(
                ToolDocs.nonNullClass(SecurityException.class),
                () -> skillCapability.load(new LoadSkillTool.Args("private-skill")));
        assertThrows(
                ToolDocs.nonNullClass(SecurityException.class),
                () -> questionCapability.ask(new AskUserTool.Args(List.of())));
        verifyNoInteractions(skills, questions);
    }

    @Test
    void workspaceMutationArgumentsCannotChangeAfterScreening(@TempDir @NonNull Path root)
            throws Exception {
        Path file = root.resolve("preserve.txt");
        Files.writeString(file, "original");
        var permit =
                capture(
                        new WriteToFileTool(),
                        Map.of(
                                "absolutePath",
                                file.toString(),
                                "codeContent",
                                "approved",
                                "overwrite",
                                false),
                        root);
        bind(permit, "agent");
        var write =
                CapabilityResolver.require(ToolDocs.nonNullClass(WorkspaceWriteCapability.class));
        assertThrows(
                ToolDocs.nonNullClass(SecurityException.class),
                () -> write.writeText("absolutePath", "substituted", false));
        assertThrows(
                ToolDocs.nonNullClass(SecurityException.class),
                () -> write.writeText("absolutePath", "approved", true));
        assertEquals("original", Files.readString(file));
        var deletion =
                capture(
                        new DeletePathTool(),
                        Map.of("absolutePath", root.toString(), "recursive", false),
                        root);
        bind(deletion, "agent");
        var delete =
                CapabilityResolver.require(ToolDocs.nonNullClass(WorkspaceWriteCapability.class));
        assertThrows(
                ToolDocs.nonNullClass(SecurityException.class),
                () -> delete.deletePath("absolutePath", true));
        assertEquals("original", Files.readString(file));
    }
}
