package top.focess.veto.vault;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import top.focess.veto.agent.continuation.RequestContinuationStore;
import top.focess.veto.agent.intercept.HitlRecordRepository;
import top.focess.veto.controller.AuthController;
import top.focess.veto.controller.dto.AuthCredentials;
import top.focess.veto.event.EventManager;
import top.focess.veto.integration.plugins.PluginDataCleanup;
import top.focess.veto.integration.plugins.storage.ScopedPluginStorage;
import top.focess.veto.model.AgentInstanceRepository;
import top.focess.veto.model.AgentPatternRepository;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.security.SignupPolicy;

class UserAdminServiceTest {
    private final @NonNull UserRegistry users = mock(UserRegistry.class);
    private final @NonNull AuthLifecycleManager lifecycle = mock(AuthLifecycleManager.class);
    private final @NonNull KeysteadVault vault = mock(KeysteadVault.class);
    private final @NonNull PlatformTransactionManager transactions =
            mock(PlatformTransactionManager.class);

    private @NonNull UserAdminService service() {
        when(lifecycle.locks()).thenReturn(new AccountLocks());
        when(vault.prepareStoreDeletion(any())).thenReturn(() -> {});
        when(transactions.getTransaction(any()))
                .thenAnswer(
                        invocation -> {
                            TransactionDefinition definition = invocation.getArgument(0);
                            if (definition == null)
                                throw new AssertionError("Missing transaction definition");
                            assertEquals(
                                    TransactionDefinition.PROPAGATION_REQUIRES_NEW,
                                    definition.getPropagationBehavior());
                            return new SimpleTransactionStatus();
                        });
        return new UserAdminService(
                users,
                mock(AgentPatternRepository.class),
                mock(SessionRepository.class),
                mock(AgentInstanceRepository.class),
                vault,
                lifecycle,
                mock(EventManager.class),
                mock(PluginDataCleanup.class),
                mock(ScopedPluginStorage.class),
                mock(HitlRecordRepository.class),
                mock(RequestContinuationStore.class),
                transactions);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void loginWaitsForAccountDeletionCommitOrRollback(boolean fails) throws Exception {
        var admin = service();
        var exists = new AtomicBoolean(true);
        var userId = UUID.randomUUID();
        var user = mock(UserEntity.class);
        when(user.getUserId()).thenReturn(userId);
        when(user.getRole()).thenReturn(UserRegistry.Role.USER);
        when(user.getUsername()).thenReturn("owner");
        when(users.findByUserId(userId)).thenReturn(Optional.of(user));
        when(users.findByUsername("owner"))
                .thenAnswer(call -> exists.get() ? Optional.of(user) : Optional.empty());

        var completing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var loginStarted = new CountDownLatch(1);
        var authenticating = new CountDownLatch(1);
        when(users.authenticate("owner", "test-password"))
                .thenAnswer(
                        invocation -> {
                            authenticating.countDown();
                            return exists.get() ? Optional.of(user) : Optional.empty();
                        });
        if (fails) {
            doThrow(new IllegalStateException("Deletion failed"))
                    .when(users)
                    .deleteByUserId(userId);
            doAnswer(
                            invocation -> {
                                completing.countDown();
                                assertTrue(release.await(5, TimeUnit.SECONDS));
                                return null;
                            })
                    .when(transactions)
                    .rollback(any());
        } else {
            doAnswer(
                            invocation -> {
                                completing.countDown();
                                assertTrue(release.await(5, TimeUnit.SECONDS));
                                exists.set(false);
                                return null;
                            })
                    .when(transactions)
                    .commit(any());
        }
        var controller =
                new AuthController(
                        new AuthService(
                                users,
                                new LoginSessionManager(),
                                vault,
                                lifecycle,
                                admin,
                                new SignupPolicy("public", "LOCAL")));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var deletion = pool.submit(() -> admin.deleteUser(userId));
            assertTrue(completing.await(5, TimeUnit.SECONDS));
            var login =
                    pool.submit(
                            () -> {
                                loginStarted.countDown();
                                try {
                                    return controller.login(
                                            new AuthCredentials("owner", "test-password"));
                                } catch (AuthException rejected) {
                                    return controller.rejected(rejected);
                                }
                            });
            assertTrue(loginStarted.await(5, TimeUnit.SECONDS));
            try {
                assertFalse(
                        authenticating.await(200, TimeUnit.MILLISECONDS),
                        "Authentication must wait for account transaction completion");
                assertFalse(
                        login.isDone(),
                        "Identity publication must wait for deletion commit or rollback");
            } finally {
                release.countDown();
            }
            if (fails) {
                var failure =
                        assertThrows(
                                ExecutionException.class, () -> deletion.get(5, TimeUnit.SECONDS));
                var cause = failure.getCause();
                if (cause == null) throw new AssertionError("Missing deletion failure");
                assertInstanceOf(IllegalStateException.class, cause);
            } else deletion.get(5, TimeUnit.SECONDS);
            assertEquals(fails ? 200 : 401, login.get(5, TimeUnit.SECONDS).getStatusCode().value());
        } finally {
            release.countDown();
        }
    }

    @Test
    void concurrentDeletionsCannotRemoveTheLastAdministrator() throws Exception {
        var admin = service();
        var count = new AtomicInteger(2);
        when(users.findByUserId(any()))
                .thenAnswer(
                        call -> {
                            var account = mock(UserEntity.class);
                            when(account.getUserId()).thenReturn(call.getArgument(0));
                            when(account.getUsername()).thenReturn("administrator");
                            return Optional.of(account);
                        });
        when(users.isAdmin(any())).thenReturn(true);
        when(users.adminCount()).thenAnswer(invocation -> (long) count.get());
        doAnswer(invocation -> count.decrementAndGet()).when(users).deleteByUserId(any());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> tryDelete(admin, UUID.randomUUID()));
            var second = pool.submit(() -> tryDelete(admin, UUID.randomUUID()));
            assertNotEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
        }
        assertEquals(1, count.get());
        verify(users, times(1)).deleteByUserId(any());
    }

    @Test
    void ordinaryProvisioningCanFinishWhileAnotherAccountIsBusy() throws Exception {
        var admin = service();
        var owner = UUID.randomUUID();
        var created = mock(UserEntity.class);
        when(created.getUserId()).thenReturn(UUID.randomUUID());
        when(users.create("new-user", "test-password", UserRegistry.Role.USER)).thenReturn(created);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor();
                var _ = lifecycle.locks().account(owner)) {
            assertSame(
                    created,
                    workers.submit(
                                    () ->
                                            admin.create(
                                                    "new-user",
                                                    "test-password",
                                                    UserRegistry.Role.USER))
                            .get(5, TimeUnit.SECONDS));
            verify(vault).createVault(created.getUserId(), "test-password");
            verify(transactions).commit(any());
        }
    }

    @Test
    void sameNameRecreationWaitsUntilCommittedVaultCleanupFinishes() throws Exception {
        var admin = service();
        var oldId = UUID.randomUUID();
        var oldAccount = mock(UserEntity.class);
        when(users.findByUserId(oldId)).thenReturn(Optional.of(oldAccount));
        var created = mock(UserEntity.class);
        when(created.getUserId()).thenReturn(UUID.randomUUID());
        when(users.create("owner", "test-password", UserRegistry.Role.USER)).thenReturn(created);
        var cleaning = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(vault.prepareStoreDeletion(oldId))
                .thenReturn(
                        () -> {
                            verify(transactions).commit(any());
                            cleaning.countDown();
                            try {
                                assertTrue(release.await(5, TimeUnit.SECONDS));
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new AssertionError(interrupted);
                            }
                        });
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var deletion = workers.submit(() -> admin.deleteUser(oldId));
            assertTrue(cleaning.await(5, TimeUnit.SECONDS));
            var started = new CountDownLatch(1);
            var registration =
                    workers.submit(
                            () -> {
                                started.countDown();
                                return admin.create(
                                        "owner", "test-password", UserRegistry.Role.USER);
                            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            try {
                assertFalse(registration.isDone());
                verify(users, never()).create(anyString(), anyString(), anyString());
            } finally {
                release.countDown();
            }
            deletion.get(5, TimeUnit.SECONDS);
            assertSame(created, registration.get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    private static boolean tryDelete(@NonNull UserAdminService admin, @NonNull UUID userId) {
        try {
            admin.deleteUser(userId);
            return true;
        } catch (IllegalArgumentException rejected) {
            assertEquals("Cannot delete the last administrator account.", rejected.getMessage());
            return false;
        }
    }
}
