package top.focess.veto.vault;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import top.focess.command.CommandManager;
import top.focess.command.CommandPermission;
import top.focess.command.CommandResult;
import top.focess.veto.command.PromptHandler;
import top.focess.veto.command.VetoCommandSender;
import top.focess.veto.command.commands.LoginCommand;
import top.focess.veto.event.EventManager;
import top.focess.veto.i18n.Msg;
import top.focess.veto.security.SignupPolicy;
import top.focess.veto.terminal.IpcServer;

class LoginCommandTest {
    @Test
    void wrongPasswordCannotBindTerminalToAnAlreadyUnlockedVault(@TempDir @NonNull Path tempDir) {
        var users = mock(UserRegistry.class);
        var owner = mock(UserEntity.class);
        var userId = UUID.randomUUID();
        when(owner.getUserId()).thenReturn(userId);
        when(owner.getUsername()).thenReturn("owner");
        when(users.findByUserId(userId)).thenReturn(Optional.of(owner));
        when(users.findByUsername("owner")).thenReturn(Optional.of(owner));
        when(users.authenticate("owner", "wrong-password")).thenReturn(Optional.empty());
        var config = new CredentialVaultConfiguration();
        config.setVaultHome(tempDir.toString());
        var vault = new KeysteadVault(config, users);
        var events = mock(EventManager.class);
        var lifecycle =
                new AuthLifecycleManager(
                        vault,
                        mock(PromptHandler.class),
                        events,
                        new LoginSessionManager(),
                        new StaticListableBeanFactory().getBeanProvider(IpcServer.class));
        var sender = mock(VetoCommandSender.class);
        when(sender.hasPermission(any(CommandPermission.class))).thenReturn(true);
        when(sender.input("Password:", true)).thenReturn("wrong-password");
        var manager = new CommandManager();
        manager.register(
                new LoginCommand(
                        new AuthService(
                                users,
                                new LoginSessionManager(),
                                mock(KeysteadVault.class),
                                lifecycle,
                                mock(UserAdminService.class),
                                new SignupPolicy("public", "LOCAL"))));
        try {
            vault.createVault(userId, "test-password");
            assertEquals(userId, vault.login("owner", "test-password"));
            assertTrue(vault.isUnlocked(userId));

            assertEquals(CommandResult.REFUSE, manager.dispatch(sender, "login owner").result());

            verify(users).authenticate("owner", "wrong-password");
            verify(sender, never()).setUser(any());
            verify(sender).output(Msg.get("error.auth.invalidCredentials"));
            assertTrue(vault.isUnlocked(userId));
            verifyNoInteractions(events);
        } finally {
            vault.logoutAll();
        }
    }

    @Test
    void verificationUnlockAndTerminalBindingShareTheAccountLock() {
        var users = mock(UserRegistry.class);
        var lifecycle = mock(AuthLifecycleManager.class);
        when(lifecycle.locks()).thenReturn(new AccountLocks());
        var sender = mock(VetoCommandSender.class);
        var alice = mock(UserEntity.class);
        var userId = UUID.randomUUID();
        when(alice.getUserId()).thenReturn(userId);
        when(alice.getUsername()).thenReturn("alice");
        when(users.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(sender.hasPermission(any(CommandPermission.class))).thenReturn(true);
        when(sender.input("Password:", true)).thenReturn("password");
        when(users.authenticate("alice", "password"))
                .thenAnswer(
                        invocation -> {
                            assertTrue(lifecycle.locks().heldByCurrentThread(userId));
                            return Optional.of(alice);
                        });
        doAnswer(
                        invocation -> {
                            assertTrue(lifecycle.locks().heldByCurrentThread(userId));
                            return null;
                        })
                .when(lifecycle)
                .login(alice, "password");
        doAnswer(
                        invocation -> {
                            assertTrue(lifecycle.locks().heldByCurrentThread(userId));
                            return null;
                        })
                .when(sender)
                .setUser(alice);
        var manager = new CommandManager();
        manager.register(
                new LoginCommand(
                        new AuthService(
                                users,
                                new LoginSessionManager(),
                                mock(KeysteadVault.class),
                                lifecycle,
                                mock(UserAdminService.class),
                                new SignupPolicy("public", "LOCAL"))));

        assertEquals(CommandResult.ALLOW, manager.dispatch(sender, "login alice").result());
        var ordered = inOrder(users, lifecycle, sender);
        ordered.verify(users).authenticate("alice", "password");
        ordered.verify(lifecycle).login(alice, "password");
        ordered.verify(sender).setUser(alice);
    }
}
