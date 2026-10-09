package top.focess.veto.vault;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.focess.command.CommandManager;
import top.focess.command.CommandPermission;
import top.focess.command.CommandResult;
import top.focess.veto.command.VetoCommandSender;
import top.focess.veto.command.commands.UserAdminCommand;
import top.focess.veto.i18n.Msg;
import top.focess.veto.security.SignupPolicy;

class UserAdminCommandTest {
    @ParameterizedTest
    @ValueSource(strings = {"create target admin", "delete target", "password target"})
    void authorityLostDuringInteractiveInputCannotMutateAccounts(@NonNull String operation) {
        for (String loss : List.of("logout", "demotion", "replacement")) {
            var actor = UUID.randomUUID();
            var identity = new AtomicReference<@Nullable UUID>(actor);
            var administrator = new AtomicBoolean(true);
            var admin = mock(UserAdminService.class);
            var users = mock(UserRegistry.class);
            var auth = mock(AuthLifecycleManager.class);
            when(auth.locks()).thenReturn(new AccountLocks());
            var sender = mock(VetoCommandSender.class);
            when(sender.hasPermission(any(CommandPermission.class))).thenReturn(true);
            when(sender.isLoggedIn()).thenAnswer(invocation -> identity.get() != null);
            when(sender.requireUserId()).thenReturn(actor);
            when(sender.userId()).thenAnswer(invocation -> identity.get());
            when(sender.requireUsername()).thenReturn("administrator");
            when(users.isAdmin(actor)).thenAnswer(invocation -> administrator.get());
            var target = mock(UserEntity.class);
            when(target.getUsername()).thenReturn("target");
            when(target.getUserId()).thenReturn(UUID.randomUUID());
            when(users.findByUsername("target")).thenReturn(Optional.of(target));
            when(sender.input(anyString(), anyBoolean()))
                    .thenAnswer(
                            invocation -> {
                                if (loss.equals("demotion")) administrator.set(false);
                                else identity.set(loss.equals("logout") ? null : UUID.randomUUID());
                                return operation.startsWith("delete") ? "yes" : "test-password";
                            });
            var manager = new CommandManager();
            manager.register(
                    new UserAdminCommand(
                            new AuthService(
                                    users,
                                    new LoginSessionManager(),
                                    mock(KeysteadVault.class),
                                    auth,
                                    admin,
                                    new SignupPolicy("invite", "LOCAL"))));

            assertEquals(
                    CommandResult.REFUSE,
                    manager.dispatch(sender, "user " + operation).result(),
                    loss);
            verify(admin, never()).create(anyString(), anyString(), anyString());
            verify(admin, never()).deleteUser(any());
            verify(admin, never()).setPassword(any(), anyString());
            verify(sender)
                    .output(
                            Msg.get(
                                    loss.equals("demotion")
                                            ? "error.auth.adminRequired"
                                            : "error.auth.actorChanged"));
        }
    }

    @Test
    void passwordPromptCannotRedirectResetToAReplacementUsername() {
        var actor = UUID.randomUUID();
        var original = UUID.randomUUID();
        var replacement = UUID.randomUUID();
        var users = mock(UserRegistry.class);
        when(users.isAdmin(actor)).thenReturn(true);
        var target = mock(UserEntity.class);
        when(target.getUserId()).thenReturn(original);
        when(users.findByUsername("target")).thenReturn(Optional.of(target));
        var lifecycle = mock(AuthLifecycleManager.class);
        when(lifecycle.locks()).thenReturn(new AccountLocks());
        var accounts = mock(UserAdminService.class);
        var sender = mock(VetoCommandSender.class);
        when(sender.hasPermission(any(CommandPermission.class))).thenReturn(true);
        when(sender.userId()).thenReturn(actor);
        when(sender.requireUserId()).thenReturn(actor);
        when(sender.input("New password for target:", true))
                .thenAnswer(
                        invocation -> {
                            var recreated = mock(UserEntity.class);
                            when(recreated.getUserId()).thenReturn(replacement);
                            when(users.findByUsername("target")).thenReturn(Optional.of(recreated));
                            return "test-password";
                        });
        var commands = new CommandManager();
        commands.register(
                new UserAdminCommand(
                        new AuthService(
                                users,
                                new LoginSessionManager(),
                                mock(KeysteadVault.class),
                                lifecycle,
                                accounts,
                                new SignupPolicy("invite", "LOCAL"))));
        assertEquals(
                CommandResult.ALLOW, commands.dispatch(sender, "user password target").result());
        verify(accounts).setPassword(original, "test-password");
        verify(accounts, never()).setPassword(eq(replacement), anyString());
        verify(users, times(1)).findByUsername("target");
    }
}
