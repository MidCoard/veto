package top.focess.veto.command.commands;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import top.focess.command.CommandManager;
import top.focess.command.CommandPermission;
import top.focess.command.CommandResult;
import top.focess.command.ExecutionResult;
import top.focess.veto.agent.TurnRecord;
import top.focess.veto.agent.TurnType;
import top.focess.veto.agent.screening.DeployerPolicyConfiguration;
import top.focess.veto.agent.screening.ProtectedSetResolver;
import top.focess.veto.agent.workspace.WorkspaceAdmissionPolicy;
import top.focess.veto.api.llm.ProviderType;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.command.VetoCommandSender;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.session.LlmConfig;
import top.focess.veto.session.SessionService;
import top.focess.veto.vault.TestUsers;

/**
 * Verifies the /session command dispatches to SessionService (create + auto-activate when idle).
 */
class SessionCommandTest {

    private static final @NonNull String CWD = currentDir();

    private final @NonNull WorkspaceAdmissionPolicy workspaceAdmission =
            new WorkspaceAdmissionPolicy(
                    new DeployerPolicyConfiguration(),
                    mock(ProtectedSetResolver.class),
                    TestUsers.registry());

    private static @NonNull String currentDir() {
        String value = System.getProperty("user.dir");
        return value == null ? "." : value;
    }

    @Test
    void historyReadsNamedSessionWithoutActivationAndBoundsLargeRecordPreviews() {
        SessionService service = mock(SessionService.class);
        VetoCommandSender sender = mock(VetoCommandSender.class);
        when(sender.hasPermission(any(CommandPermission.class))).thenReturn(true);
        when(sender.isLoggedIn()).thenReturn(true);
        when(sender.requireUserId()).thenReturn(TestUsers.ALICE);
        when(sender.cwd()).thenReturn(CWD);
        String largeOutput = "a".repeat(3_999) + "😀" + "b".repeat(5_000);
        when(service.history("40", TestUsers.ALICE, CWD))
                .thenReturn(
                        List.of(
                                new TurnRecord(
                                        1,
                                        TurnType.AGENT_INIT,
                                        Map.of("system_prompt", "private setup"),
                                        null),
                                TurnRecord.userPrompt(2, "old question"),
                                TurnRecord.assistantResponse(3, "old answer"),
                                TurnRecord.toolResponse(4, "call", largeOutput, true),
                                TurnRecord.userInterrupt(5, "user feedback")));
        CommandManager manager = new CommandManager();
        manager.register(new SessionCommand(service, workspaceAdmission));

        assertEquals(
                CommandResult.ALLOW, manager.dispatch(sender, "session history 40 2").result());
        ArgumentCaptor<String> output = ArgumentCaptor.forClass(String.class);
        verify(sender, times(3)).output(output.capture());
        assertEquals(
                "Conversation history: 2 of 4 effective records (primary agent).",
                output.getAllValues().get(0));
        assertEquals(
                "[TOOL_RESPONSE #4]\n"
                        + "a".repeat(3_999)
                        + "\n[Preview truncated; open this session's Web Records for the full content.]",
                output.getAllValues().get(1));
        assertEquals("[USER_INTERRUPT #5]\nuser feedback", output.getAllValues().get(2));
        verify(service, never()).activate(anyString(), anyString(), any(), anyString());
        verify(service, never()).activeSession(anyString());
    }

    @Test
    void historyDefaultsToRecentFortyAndRejectsInvalidLimitsBeforeLoading() {
        SessionService service = mock(SessionService.class);
        VetoCommandSender sender = mock(VetoCommandSender.class);
        when(sender.hasPermission(any(CommandPermission.class))).thenReturn(true);
        when(sender.isLoggedIn()).thenReturn(true);
        when(sender.requireUserId()).thenReturn(TestUsers.ALICE);
        when(sender.cwd()).thenReturn(CWD);
        when(service.history("old", TestUsers.ALICE, CWD))
                .thenReturn(
                        IntStream.rangeClosed(1, 41)
                                .mapToObj(i -> TurnRecord.userPrompt(i, "question " + i))
                                .toList());
        CommandManager manager = new CommandManager();
        manager.register(new SessionCommand(service, workspaceAdmission));

        assertEquals(CommandResult.ALLOW, manager.dispatch(sender, "session history old").result());
        verify(sender).output("Conversation history: 40 of 41 effective records (primary agent).");
        verify(sender, never()).output("[USER_PROMPT #1]\nquestion 1");
        verify(sender).output("[USER_PROMPT #2]\nquestion 2");
        clearInvocations(service);
        for (String limit : List.of("0", "201", "nope")) {
            assertEquals(
                    CommandResult.REFUSE,
                    manager.dispatch(sender, "session history old " + limit).result());
        }
        verifyNoInteractions(service);
        when(service.history("missing", TestUsers.ALICE, CWD))
                .thenThrow(new IllegalArgumentException("Session unavailable"));
        assertEquals(
                CommandResult.REFUSE, manager.dispatch(sender, "session history missing").result());
        verify(sender).output("Session unavailable");
    }

    @Test
    void createAutoActivatesWhenIdle() {
        SessionService service = mock(SessionService.class);
        SessionEntity session = new SessionEntity(TestUsers.ALICE, "coder");
        when(service.createSession(
                        TestUsers.ALICE,
                        "coder",
                        null,
                        List.of(CWD),
                        0,
                        ToolResultPresentationMode.BASIC,
                        List.of()))
                .thenReturn(session);
        when(service.activeSession("term-1")).thenReturn(Optional.empty());
        when(service.activate("term-1", "coder", TestUsers.ALICE, CWD))
                .thenReturn(Optional.of(new LlmConfig(ProviderType.DEEPSEEK, "deepseek-v4", "k")));

        VetoCommandSender sender = mock(VetoCommandSender.class);
        when(sender.hasPermission(any(CommandPermission.class))).thenReturn(true);
        when(sender.isLoggedIn()).thenReturn(true);
        when(sender.username()).thenReturn("alice");
        when(sender.userId()).thenReturn(TestUsers.ALICE);
        when(sender.requireUserId()).thenReturn(TestUsers.ALICE);
        when(sender.terminalId()).thenReturn("term-1");
        when(sender.cwd()).thenReturn(CWD);

        CommandManager manager = new CommandManager();
        manager.register(new SessionCommand(service, workspaceAdmission));

        ExecutionResult result = manager.dispatch(sender, "session create coder");

        assertEquals(CommandResult.ALLOW, result.result());
        verify(service)
                .createSession(
                        TestUsers.ALICE,
                        "coder",
                        null,
                        List.of(CWD),
                        0,
                        ToolResultPresentationMode.BASIC,
                        List.of());
        verify(service).activate("term-1", "coder", TestUsers.ALICE, CWD);
    }

    @Test
    void createDoesNotAutoActivateWhenBusy() {
        SessionService service = mock(SessionService.class);
        SessionEntity session = new SessionEntity(TestUsers.ALICE, "coder");
        when(service.createSession(
                        TestUsers.ALICE,
                        "coder",
                        null,
                        List.of(CWD),
                        0,
                        ToolResultPresentationMode.BASIC,
                        List.of()))
                .thenReturn(session);
        // A session is already active on this terminal -> do not auto-activate.
        when(service.activeSession("term-1")).thenReturn(Optional.of("existing-session-id"));

        VetoCommandSender sender = mock(VetoCommandSender.class);
        when(sender.hasPermission(any(CommandPermission.class))).thenReturn(true);
        when(sender.isLoggedIn()).thenReturn(true);
        when(sender.username()).thenReturn("alice");
        when(sender.userId()).thenReturn(TestUsers.ALICE);
        when(sender.requireUserId()).thenReturn(TestUsers.ALICE);
        when(sender.terminalId()).thenReturn("term-1");
        when(sender.cwd()).thenReturn(CWD);

        CommandManager manager = new CommandManager();
        manager.register(new SessionCommand(service, workspaceAdmission));

        ExecutionResult result = manager.dispatch(sender, "session create coder");

        assertEquals(CommandResult.ALLOW, result.result());
        verify(service)
                .createSession(
                        TestUsers.ALICE,
                        "coder",
                        null,
                        List.of(CWD),
                        0,
                        ToolResultPresentationMode.BASIC,
                        List.of());
        verify(service, never()).activate(anyString(), anyString(), any(), anyString());
    }

    @Test
    void createRefusesUnknownPattern() {
        SessionService service = mock(SessionService.class);
        when(service.createSession(
                        TestUsers.ALICE,
                        "nope",
                        null,
                        List.of(CWD),
                        0,
                        ToolResultPresentationMode.BASIC,
                        List.of()))
                .thenThrow(new IllegalArgumentException("Pattern not found: nope"));

        VetoCommandSender sender = mock(VetoCommandSender.class);
        when(sender.hasPermission(any(CommandPermission.class))).thenReturn(true);
        when(sender.isLoggedIn()).thenReturn(true);
        when(sender.username()).thenReturn("alice");
        when(sender.userId()).thenReturn(TestUsers.ALICE);
        when(sender.requireUserId()).thenReturn(TestUsers.ALICE);
        when(sender.terminalId()).thenReturn("term-1");
        when(sender.cwd()).thenReturn(CWD);

        CommandManager manager = new CommandManager();
        manager.register(new SessionCommand(service, workspaceAdmission));

        ExecutionResult result = manager.dispatch(sender, "session create nope");
        assertEquals(CommandResult.REFUSE, result.result());
        verify(sender).output("Pattern not found: nope");
    }

    @Test
    void createWithCustomNamePersistsAndActivates() {
        SessionService service = mock(SessionService.class);
        SessionEntity session = new SessionEntity(TestUsers.ALICE, "mysession");
        when(service.createSession(
                        TestUsers.ALICE,
                        "coder",
                        "mysession",
                        List.of(CWD),
                        0,
                        ToolResultPresentationMode.BASIC,
                        List.of("provider", "tools")))
                .thenReturn(session);
        when(service.activeSession("term-1")).thenReturn(Optional.empty());
        when(service.activate("term-1", "mysession", TestUsers.ALICE, CWD))
                .thenReturn(Optional.of(new LlmConfig(ProviderType.DEEPSEEK, "deepseek-v4", "k")));

        VetoCommandSender sender = mock(VetoCommandSender.class);
        when(sender.hasPermission(any(CommandPermission.class))).thenReturn(true);
        when(sender.isLoggedIn()).thenReturn(true);
        when(sender.username()).thenReturn("alice");
        when(sender.userId()).thenReturn(TestUsers.ALICE);
        when(sender.requireUserId()).thenReturn(TestUsers.ALICE);
        when(sender.terminalId()).thenReturn("term-1");
        when(sender.cwd()).thenReturn(CWD);

        CommandManager manager = new CommandManager();
        manager.register(new SessionCommand(service, workspaceAdmission));

        ExecutionResult result =
                manager.dispatch(sender, "session create coder mysession provider,tools");

        assertEquals(CommandResult.ALLOW, result.result());
        verify(service)
                .createSession(
                        TestUsers.ALICE,
                        "coder",
                        "mysession",
                        List.of(CWD),
                        0,
                        ToolResultPresentationMode.BASIC,
                        List.of("provider", "tools"));
        verify(service).activate("term-1", "mysession", TestUsers.ALICE, CWD);
    }
}
