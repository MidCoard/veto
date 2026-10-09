package top.focess.veto.vault;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import top.focess.command.CommandManager;
import top.focess.command.CommandPermission;
import top.focess.command.CommandResult;
import top.focess.veto.agent.continuation.RequestContinuationStore;
import top.focess.veto.agent.intercept.HitlRecordRepository;
import top.focess.veto.api.event.*;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contribution.Contribution;
import top.focess.veto.command.VetoCommandSender;
import top.focess.veto.command.commands.SignupCommand;
import top.focess.veto.command.commands.UserAdminCommand;
import top.focess.veto.controller.AuthController;
import top.focess.veto.controller.dto.AuthCredentials;
import top.focess.veto.controller.dto.CreateUserRequest;
import top.focess.veto.integration.plugins.PluginDataCleanup;
import top.focess.veto.integration.plugins.WorkflowPluginFixture;
import top.focess.veto.integration.plugins.storage.ScopedPluginStorage;
import top.focess.veto.model.AgentInstanceRepository;
import top.focess.veto.model.AgentPatternRepository;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.security.SignupPolicy;

/** Real plugin admission/dispatch, shared workflows and transport adapters with isolated stores. */
class RegistrationHooksTest {
    public static final class CancelRegistration implements Listener {
        private final @NonNull AtomicInteger calls = new AtomicInteger();

        @EventHandler
        public void before(@NonNull BeforeUserRegisterEvent event) {
            assertInstanceOf(Scope.GlobalScope.class, event.scope());
            assertEquals("new-user", event.username());
            assertTrue(UserRegistry.isValidRole(event.role()));
            calls.incrementAndGet();
            event.setCancelled(true);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"setup", "signup", "http-admin", "terminal-admin"})
    void pluginCancellationPreventsEveryCreationEntryPoint(@NonNull String entry) throws Exception {
        var listener = new CancelRegistration();
        try (var plugins =
                new WorkflowPluginFixture(
                        List.of(
                                Contribution.of(
                                        StandardContributionPoints.LISTENERS,
                                        "registration",
                                        listener)))) {
            var users = mock(UserRegistry.class);
            var vault = mock(KeysteadVault.class);
            var lifecycle = mock(AuthLifecycleManager.class);
            when(lifecycle.locks()).thenReturn(new AccountLocks());
            var transactions = mock(PlatformTransactionManager.class);
            var accounts = accounts(users, vault, lifecycle, plugins, transactions);
            var sessions = new LoginSessionManager();
            var auth =
                    new AuthService(
                            users,
                            sessions,
                            vault,
                            lifecycle,
                            accounts,
                            new SignupPolicy("public", "LOCAL"));
            var controller = new AuthController(auth);
            var sender = mock(VetoCommandSender.class);
            when(sender.hasPermission(any(CommandPermission.class))).thenReturn(true);
            when(sender.input(anyString(), anyBoolean())).thenReturn("test-password");
            if (entry.equals("setup")) {
                var rejection =
                        assertThrows(
                                AuthException.class,
                                () ->
                                        controller.setup(
                                                new AuthCredentials("new-user", "test-password")));
                assertEquals(403, controller.rejected(rejection).getStatusCode().value());
            } else if (entry.equals("signup")) {
                var commands = new CommandManager();
                commands.register(new SignupCommand(auth));
                assertEquals(
                        CommandResult.REFUSE,
                        commands.dispatch(sender, "signup new-user").result());
            } else {
                var administrator = UUID.randomUUID();
                when(users.isAdmin(administrator)).thenReturn(true);
                if (entry.equals("http-admin")) {
                    var token = sessions.createLoginSession(administrator, "administrator");
                    var rejection =
                            assertThrows(
                                    AuthException.class,
                                    () ->
                                            controller.addUser(
                                                    token,
                                                    new CreateUserRequest(
                                                            "new-user", "test-password", "USER")));
                    assertEquals(403, controller.rejected(rejection).getStatusCode().value());
                    assertEquals(1, sessions.activeLoginSessionCount());
                    sessions.revokeToken(token);
                } else {
                    when(sender.userId()).thenReturn(administrator);
                    when(sender.requireUserId()).thenReturn(administrator);
                    var commands = new CommandManager();
                    commands.register(new UserAdminCommand(auth));
                    assertEquals(
                            CommandResult.REFUSE,
                            commands.dispatch(sender, "user create new-user").result());
                }
            }
            assertEquals(1, listener.calls.get());
            verify(users, never()).create(anyString(), anyString(), anyString());
            verifyNoInteractions(transactions, vault);
            verify(lifecycle, never()).signup(any(), anyString());
            verify(lifecycle, never()).login(any(), anyString());
            verify(sender, never()).setUser(any());
            assertEquals(0, sessions.activeLoginSessionCount());
        }
    }

    public static final class CancelThenPermit implements Listener {
        private final @NonNull AtomicInteger skipped = new AtomicInteger();

        @EventHandler(priority = EventPriority.HIGHEST)
        public void cancel(@NonNull BeforeUserRegisterEvent event) {
            event.setCancelled(true);
        }

        @EventHandler(priority = EventPriority.NORMAL, notCallIfCancelled = true)
        public void skip(@NonNull BeforeUserRegisterEvent event) {
            skipped.incrementAndGet();
        }

        @EventHandler(priority = EventPriority.LOWEST)
        public void permit(@NonNull BeforeUserRegisterEvent event) {
            event.setCancelled(false);
        }
    }

    public static final class PreventRegistration implements Listener {
        @EventHandler
        public void before(@NonNull BeforeUserRegisterEvent event) {
            event.prevent();
        }
    }

    public static final class FailingRegistration implements Listener {
        @EventHandler
        public void before(@NonNull BeforeUserRegisterEvent event) {
            throw new IllegalStateException("fixture listener failure");
        }
    }

    @Test
    void cancellationIsReversibleAndOptOutIsRespected() throws Exception {
        var listener = new CancelThenPermit();
        assertProvisioned(listener);
        assertEquals(0, listener.skipped.get());
    }

    @Test
    void preventFlagOnlyStopsPropagation() throws Exception {
        assertProvisioned(new PreventRegistration());
    }

    @Test
    void ordinaryPluginFailureFollowsContainedFailureContract() throws Exception {
        assertProvisioned(new FailingRegistration());
    }

    private static void assertProvisioned(@NonNull Listener listener) throws Exception {
        try (var plugins =
                new WorkflowPluginFixture(
                        List.of(
                                Contribution.of(
                                        StandardContributionPoints.LISTENERS,
                                        "registration",
                                        listener)))) {
            var users = mock(UserRegistry.class);
            var vault = mock(KeysteadVault.class);
            var lifecycle = mock(AuthLifecycleManager.class);
            when(lifecycle.locks()).thenReturn(new AccountLocks());
            var transactions = mock(PlatformTransactionManager.class);
            when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
            var user = mock(UserEntity.class);
            when(user.getUserId()).thenReturn(UUID.randomUUID());
            when(users.create("new-user", "test-password", "USER")).thenReturn(user);
            assertSame(
                    user,
                    accounts(users, vault, lifecycle, plugins, transactions)
                            .create("new-user", "test-password", "USER"));
            verify(vault).createVault(user.getUserId(), "test-password");
            verify(transactions).commit(any());
        }
    }

    @Test
    void invalidInputAndMissingAuthorityNeverReachPluginAdmission() throws Exception {
        var listener = new CancelRegistration();
        try (var plugins =
                new WorkflowPluginFixture(
                        List.of(
                                Contribution.of(
                                        StandardContributionPoints.LISTENERS,
                                        "registration",
                                        listener)))) {
            var users = mock(UserRegistry.class);
            var vault = mock(KeysteadVault.class);
            var lifecycle = mock(AuthLifecycleManager.class);
            when(lifecycle.locks()).thenReturn(new AccountLocks());
            var transactions = mock(PlatformTransactionManager.class);
            var auth =
                    new AuthService(
                            users,
                            new LoginSessionManager(),
                            vault,
                            lifecycle,
                            accounts(users, vault, lifecycle, plugins, transactions),
                            new SignupPolicy("public", "LOCAL"));
            assertEquals(
                    AuthException.Kind.INVALID_INPUT,
                    assertThrows(
                                    AuthException.class,
                                    () -> auth.setup("../invalid", "test-password"))
                            .kind());
            assertEquals(
                    AuthException.Kind.INVALID_SESSION,
                    assertThrows(
                                    AuthException.class,
                                    () ->
                                            auth.createUser(
                                                    "invalid-token",
                                                    "new-user",
                                                    "test-password",
                                                    "ADMIN"))
                            .kind());
            assertEquals(0, listener.calls.get());
            verifyNoInteractions(transactions, vault);
        }
    }

    private static @NonNull UserAdminService accounts(
            @NonNull UserRegistry users,
            @NonNull KeysteadVault vault,
            @NonNull AuthLifecycleManager lifecycle,
            @NonNull WorkflowPluginFixture plugins,
            @NonNull PlatformTransactionManager transactions) {
        return new UserAdminService(
                users,
                mock(AgentPatternRepository.class),
                mock(SessionRepository.class),
                mock(AgentInstanceRepository.class),
                vault,
                lifecycle,
                plugins.events,
                mock(PluginDataCleanup.class),
                mock(ScopedPluginStorage.class),
                mock(HitlRecordRepository.class),
                mock(RequestContinuationStore.class),
                transactions);
    }
}
