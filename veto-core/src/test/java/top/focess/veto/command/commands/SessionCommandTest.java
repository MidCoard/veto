package top.focess.veto.command.commands;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.command.CommandManager;
import top.focess.command.CommandPermission;
import top.focess.command.CommandResult;
import top.focess.command.ExecutionResult;
import top.focess.veto.command.VetoCommandSender;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.session.LlmConfig;
import top.focess.veto.session.SessionService;

/**
 * Verifies the /session command dispatches to SessionService (create + auto-activate when idle).
 */
class SessionCommandTest {

    private static final @NonNull String CWD = currentDir();

    private static @NonNull String currentDir() {
        String value = System.getProperty("user.dir");
        return value == null ? "." : value;
    }

    @Test
    void createAutoActivatesWhenIdle() {
        SessionService service =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionService.class));
        SessionEntity session = new SessionEntity("alice", "coder");
        when(service.createSession("alice", "coder", null, CWD)).thenReturn(session);
        when(service.activeSession("term-1")).thenReturn(Optional.empty());
        when(service.activate("term-1", "coder", "alice", CWD))
                .thenReturn(Optional.of(new LlmConfig(ProviderType.DEEPSEEK, "deepseek-v4", "k")));

        VetoCommandSender sender =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(VetoCommandSender.class));
        when(sender.hasPermission(
                        any(
                                top.focess.veto.agent.mcp.ToolDocs.nonNullClass(
                                        CommandPermission.class))))
                .thenReturn(true);
        when(sender.isLoggedIn()).thenReturn(true);
        when(sender.username()).thenReturn("alice");
        when(sender.requireUsername()).thenReturn("alice");
        when(sender.terminalId()).thenReturn("term-1");
        when(sender.cwd()).thenReturn(CWD);

        CommandManager manager = new CommandManager();
        manager.register(new SessionCommand(service));

        ExecutionResult result = manager.dispatch(sender, "session create coder");

        assertEquals(CommandResult.ALLOW, result.result());
        verify(service).createSession("alice", "coder", null, CWD);
        verify(service).activate("term-1", "coder", "alice", CWD);
    }

    @Test
    void createDoesNotAutoActivateWhenBusy() {
        SessionService service =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionService.class));
        SessionEntity session = new SessionEntity("alice", "coder");
        when(service.createSession("alice", "coder", null, CWD)).thenReturn(session);
        // A session is already active on this terminal -> do not auto-activate.
        when(service.activeSession("term-1")).thenReturn(Optional.of("existing-session-id"));

        VetoCommandSender sender =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(VetoCommandSender.class));
        when(sender.hasPermission(
                        any(
                                top.focess.veto.agent.mcp.ToolDocs.nonNullClass(
                                        CommandPermission.class))))
                .thenReturn(true);
        when(sender.isLoggedIn()).thenReturn(true);
        when(sender.username()).thenReturn("alice");
        when(sender.requireUsername()).thenReturn("alice");
        when(sender.terminalId()).thenReturn("term-1");
        when(sender.cwd()).thenReturn(CWD);

        CommandManager manager = new CommandManager();
        manager.register(new SessionCommand(service));

        ExecutionResult result = manager.dispatch(sender, "session create coder");

        assertEquals(CommandResult.ALLOW, result.result());
        verify(service).createSession("alice", "coder", null, CWD);
        verify(service, never()).activate(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void createRefusesUnknownPattern() {
        SessionService service =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionService.class));
        when(service.createSession("alice", "nope", null, CWD))
                .thenThrow(new IllegalArgumentException("Pattern not found: nope"));

        VetoCommandSender sender =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(VetoCommandSender.class));
        when(sender.hasPermission(
                        any(
                                top.focess.veto.agent.mcp.ToolDocs.nonNullClass(
                                        CommandPermission.class))))
                .thenReturn(true);
        when(sender.isLoggedIn()).thenReturn(true);
        when(sender.username()).thenReturn("alice");
        when(sender.requireUsername()).thenReturn("alice");
        when(sender.terminalId()).thenReturn("term-1");
        when(sender.cwd()).thenReturn(CWD);

        CommandManager manager = new CommandManager();
        manager.register(new SessionCommand(service));

        ExecutionResult result = manager.dispatch(sender, "session create nope");
        assertEquals(CommandResult.REFUSE, result.result());
        verify(sender).output("Pattern not found: nope");
    }

    @Test
    void createWithCustomNamePersistsAndActivates() {
        SessionService service =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionService.class));
        SessionEntity session = new SessionEntity("alice", "mysession");
        when(service.createSession("alice", "coder", "mysession", CWD)).thenReturn(session);
        when(service.activeSession("term-1")).thenReturn(Optional.empty());
        when(service.activate("term-1", "mysession", "alice", CWD))
                .thenReturn(Optional.of(new LlmConfig(ProviderType.DEEPSEEK, "deepseek-v4", "k")));

        VetoCommandSender sender =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(VetoCommandSender.class));
        when(sender.hasPermission(
                        any(
                                top.focess.veto.agent.mcp.ToolDocs.nonNullClass(
                                        CommandPermission.class))))
                .thenReturn(true);
        when(sender.isLoggedIn()).thenReturn(true);
        when(sender.username()).thenReturn("alice");
        when(sender.requireUsername()).thenReturn("alice");
        when(sender.terminalId()).thenReturn("term-1");
        when(sender.cwd()).thenReturn(CWD);

        CommandManager manager = new CommandManager();
        manager.register(new SessionCommand(service));

        ExecutionResult result = manager.dispatch(sender, "session create coder mysession");

        assertEquals(CommandResult.ALLOW, result.result());
        verify(service).createSession("alice", "coder", "mysession", CWD);
        verify(service).activate("term-1", "mysession", "alice", CWD);
    }
}
