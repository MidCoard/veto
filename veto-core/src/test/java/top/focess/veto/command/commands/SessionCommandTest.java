package top.focess.veto.command.commands;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.command.CommandManager;
import top.focess.command.CommandPermission;
import top.focess.command.CommandResult;
import top.focess.command.ExecutionResult;
import top.focess.veto.agent.screening.DeployerPolicyConfiguration;
import top.focess.veto.agent.screening.ProtectedSetResolver;
import top.focess.veto.agent.workspace.WorkspaceAdmissionPolicy;
import top.focess.veto.api.llm.ProviderType;
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
    void createAutoActivatesWhenIdle() {
        SessionService service = mock(SessionService.class);
        SessionEntity session = new SessionEntity(TestUsers.ALICE, "coder");
        when(service.createSession(TestUsers.ALICE, "coder", null, List.of(CWD)))
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
        verify(service).createSession(TestUsers.ALICE, "coder", null, List.of(CWD));
        verify(service).activate("term-1", "coder", TestUsers.ALICE, CWD);
    }

    @Test
    void createDoesNotAutoActivateWhenBusy() {
        SessionService service = mock(SessionService.class);
        SessionEntity session = new SessionEntity(TestUsers.ALICE, "coder");
        when(service.createSession(TestUsers.ALICE, "coder", null, List.of(CWD)))
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
        verify(service).createSession(TestUsers.ALICE, "coder", null, List.of(CWD));
        verify(service, never()).activate(anyString(), anyString(), any(), anyString());
    }

    @Test
    void createRefusesUnknownPattern() {
        SessionService service = mock(SessionService.class);
        when(service.createSession(TestUsers.ALICE, "nope", null, List.of(CWD)))
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
        when(service.createSession(TestUsers.ALICE, "coder", "mysession", List.of(CWD)))
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

        ExecutionResult result = manager.dispatch(sender, "session create coder mysession");

        assertEquals(CommandResult.ALLOW, result.result());
        verify(service).createSession(TestUsers.ALICE, "coder", "mysession", List.of(CWD));
        verify(service).activate("term-1", "mysession", TestUsers.ALICE, CWD);
    }
}
