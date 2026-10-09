package top.focess.veto.vault;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.command.CommandManager;
import top.focess.command.CommandPermission;
import top.focess.command.CommandResult;
import top.focess.veto.command.VetoCommandSender;
import top.focess.veto.command.commands.SignupCommand;
import top.focess.veto.security.SignupPolicy;

class SignupCommandTest {

    private static @NonNull VetoCommandSender sender() {
        var sender = mock(VetoCommandSender.class);
        when(sender.hasPermission(any(CommandPermission.class))).thenReturn(true);
        when(sender.input("Choose a password:", true)).thenReturn("prompted-password");
        return sender;
    }

    private @NonNull CommandManager manager(
            @NonNull UserRegistry users, @NonNull AuthLifecycleManager auth, @NonNull String mode) {
        var manager = new CommandManager();

        var accounts = mock(UserAdminService.class);
        when(accounts.create(anyString(), anyString(), anyString()))
                .thenAnswer(
                        invocation -> {
                            var created =
                                    users.create(
                                            invocation.getArgument(0),
                                            invocation.getArgument(1),
                                            invocation.getArgument(2));
                            return created != null
                                    ? created
                                    : users.findByUsername(invocation.getArgument(0)).orElseThrow();
                        });
        manager.register(
                new SignupCommand(
                        new AuthService(
                                users,
                                new LoginSessionManager(),
                                mock(KeysteadVault.class),
                                auth,
                                accounts,
                                new SignupPolicy(mode, "LOCAL"))));
        return manager;
    }

    private static void account(@NonNull UserRegistry users, @NonNull String name) {
        var account = mock(UserEntity.class);
        when(account.getUserId()).thenReturn(UUID.randomUUID());
        when(account.getUsername()).thenReturn(name);
        when(account.getRole()).thenReturn(UserRegistry.Role.ADMIN);
        when(users.findByUsername(name)).thenReturn(Optional.of(account));
    }

    @Test
    void passwordIsAlwaysPromptedWithMasking() {
        var users = mock(UserRegistry.class);
        var auth = mock(AuthLifecycleManager.class);
        when(auth.locks()).thenReturn(new AccountLocks());
        var sender = sender();
        account(users, "alice");

        var result = manager(users, auth, "solo").dispatch(sender, "signup alice");

        assertEquals(CommandResult.ALLOW, result.result());
        verify(sender).input("Choose a password:", true);
        verify(users).create("alice", "prompted-password", UserRegistry.Role.ADMIN);
        verify(auth).signup(any(UserEntity.class), eq("prompted-password"));
    }

    @Test
    void commandLinePasswordCannotCreateAnAccount() {
        var users = mock(UserRegistry.class);
        var auth = mock(AuthLifecycleManager.class);
        when(auth.locks()).thenReturn(new AccountLocks());

        var result =
                manager(users, auth, "solo").dispatch(sender(), "signup alice command-password");

        assertNotEquals(CommandResult.ALLOW, result.result());
        verify(users, never()).create(anyString(), anyString(), anyString());
        verify(auth, never()).signup(any(UserEntity.class), anyString());
    }

    @Test
    void bootstrapCompletedDuringPromptRejectsSoloSignup() {
        var users = mock(UserRegistry.class);
        var auth = mock(AuthLifecycleManager.class);
        when(auth.locks()).thenReturn(new AccountLocks());
        var administrators = new AtomicInteger();
        when(users.adminCount()).thenAnswer(invocation -> (long) administrators.get());
        var sender = sender();
        when(sender.input("Choose a password:", true))
                .thenAnswer(
                        invocation -> {
                            administrators.incrementAndGet();
                            return "prompted-password";
                        });
        var result = manager(users, auth, "solo").dispatch(sender, "signup alice");

        assertEquals(CommandResult.REFUSE, result.result());
        verify(users, never()).create(anyString(), anyString(), anyString());
        verify(auth, never()).signup(any(UserEntity.class), anyString());
    }

    @Test
    void bootstrapCompletedDuringPromptCreatesOrdinaryPublicAccount() {
        var users = mock(UserRegistry.class);
        var auth = mock(AuthLifecycleManager.class);
        when(auth.locks()).thenReturn(new AccountLocks());
        var administrators = new AtomicInteger();
        when(users.adminCount()).thenAnswer(invocation -> (long) administrators.get());
        var sender = sender();
        when(sender.input("Choose a password:", true))
                .thenAnswer(
                        invocation -> {
                            administrators.incrementAndGet();
                            return "prompted-password";
                        });
        account(users, "alice");
        var result = manager(users, auth, "public").dispatch(sender, "signup alice");

        assertEquals(CommandResult.ALLOW, result.result());
        verify(users).create("alice", "prompted-password", UserRegistry.Role.USER);
    }

    @Test
    void simultaneousBootstrapRequestsCreateOnlyOneAdministrator() throws Exception {
        var users = mock(UserRegistry.class);
        var auth = mock(AuthLifecycleManager.class);
        when(auth.locks()).thenReturn(new AccountLocks());
        var administratorCount = new AtomicInteger();
        var prompts = new CountDownLatch(2);
        when(users.adminCount()).thenAnswer(invocation -> (long) administratorCount.get());
        when(users.create(anyString(), anyString(), anyString()))
                .thenAnswer(
                        invocation -> {
                            if (UserRegistry.Role.ADMIN.equals(invocation.getArgument(2))) {
                                assertTrue(auth.locks().exclusiveByCurrentThread());
                                administratorCount.incrementAndGet();
                            }
                            var created = mock(UserEntity.class);
                            when(created.getUserId()).thenReturn(UUID.randomUUID());
                            when(created.getUsername()).thenReturn(invocation.getArgument(0));
                            when(created.getRole()).thenReturn(invocation.getArgument(2));
                            return created;
                        });
        var alice = sender();
        var bob = sender();
        for (var sender : new VetoCommandSender[] {alice, bob}) {
            when(sender.input("Choose a password:", true))
                    .thenAnswer(
                            invocation -> {
                                prompts.countDown();
                                assertTrue(prompts.await(5, TimeUnit.SECONDS));
                                return "prompted-password";
                            });
        }
        account(users, "alice");
        account(users, "bob");
        var manager = manager(users, auth, "public");
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = workers.submit(() -> manager.dispatch(alice, "signup alice").result());
            var second = workers.submit(() -> manager.dispatch(bob, "signup bob").result());
            assertEquals(CommandResult.ALLOW, first.get(10, TimeUnit.SECONDS));
            assertEquals(CommandResult.ALLOW, second.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, administratorCount.get());
        verify(users, times(1))
                .create(anyString(), eq("prompted-password"), eq(UserRegistry.Role.ADMIN));
        verify(users, times(1))
                .create(anyString(), eq("prompted-password"), eq(UserRegistry.Role.USER));
    }
}
