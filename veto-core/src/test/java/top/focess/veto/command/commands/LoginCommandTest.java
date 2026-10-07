package top.focess.veto.command.commands;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import top.focess.command.CommandManager;
import top.focess.command.CommandPermission;
import top.focess.command.CommandResult;
import top.focess.veto.command.VetoCommandSender;
import top.focess.veto.vault.AuthLifecycleManager;
import top.focess.veto.vault.UserEntity;
import top.focess.veto.vault.UserRegistry;

class LoginCommandTest {
    @Test
    void authenticationUnlockAndTerminalBindingShareLogoutMonitor() {
        var users = mock(UserRegistry.class);
        var lifecycle = mock(AuthLifecycleManager.class);
        var sender = mock(VetoCommandSender.class);
        var alice = mock(UserEntity.class);
        when(sender.hasPermission(any(CommandPermission.class))).thenReturn(true);
        when(sender.input("Password:", true)).thenReturn("password");
        when(users.authenticate("alice", "password"))
                .thenAnswer(
                        invocation -> {
                            assertTrue(Thread.holdsLock(lifecycle));
                            return Optional.of(alice);
                        });
        doAnswer(
                        invocation -> {
                            assertTrue(Thread.holdsLock(lifecycle));
                            return null;
                        })
                .when(lifecycle)
                .login("alice", "password");
        doAnswer(
                        invocation -> {
                            assertTrue(Thread.holdsLock(lifecycle));
                            return null;
                        })
                .when(sender)
                .setUser(alice);
        var manager = new CommandManager();
        manager.register(new LoginCommand(users, lifecycle));

        assertEquals(CommandResult.ALLOW, manager.dispatch(sender, "login alice").result());
        var ordered = inOrder(users, lifecycle, sender);
        ordered.verify(users).authenticate("alice", "password");
        ordered.verify(lifecycle).login("alice", "password");
        ordered.verify(sender).setUser(alice);
    }
}
