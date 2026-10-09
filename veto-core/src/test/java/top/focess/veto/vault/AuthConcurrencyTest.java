package top.focess.veto.vault;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static top.focess.veto.vault.TestUsers.*;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import top.focess.veto.command.PromptHandler;
import top.focess.veto.command.VetoCommandSender;
import top.focess.veto.event.EventManager;
import top.focess.veto.security.SignupPolicy;
import top.focess.veto.terminal.IpcServer;

class AuthConcurrencyTest {
    @Test
    void passwordChangeBlocksOnlyItsActorAndTargetAndDoesNotBlockStatus() throws Exception {
        var users = registry();
        var tokens = new LoginSessionManager();
        var vault = mock(KeysteadVault.class);
        when(vault.login("bob", "test-password")).thenReturn(BOB);
        var bobAccount = users.findByUserId(BOB);
        when(users.authenticate("bob", "test-password")).thenReturn(bobAccount);
        when(users.authenticate("alice", "old-password")).thenReturn(Optional.empty());
        var lifecycle =
                new AuthLifecycleManager(
                        vault,
                        mock(PromptHandler.class),
                        mock(EventManager.class),
                        tokens,
                        new StaticListableBeanFactory().getBeanProvider(IpcServer.class));
        var accounts = mock(UserAdminService.class);
        var sender = mock(VetoCommandSender.class);
        when(sender.userId()).thenReturn(ADMIN);
        var changing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(
                        call -> {
                            assertTrue(lifecycle.locks().heldByCurrentThread(ADMIN));
                            assertTrue(lifecycle.locks().heldByCurrentThread(ALICE));
                            changing.countDown();
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                            return null;
                        })
                .when(accounts)
                .setPassword(ALICE, "new-password");
        var auth =
                new AuthService(
                        users,
                        tokens,
                        vault,
                        lifecycle,
                        accounts,
                        new SignupPolicy("public", "LOCAL"));
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var change =
                    workers.submit(() -> auth.setPassword(sender, ADMIN, ALICE, "new-password"));
            assertTrue(changing.await(5, TimeUnit.SECONDS));
            var aliceStarted = new CountDownLatch(1);
            var alice =
                    workers.submit(
                            () -> {
                                aliceStarted.countDown();
                                return assertThrows(
                                        AuthException.class,
                                        () -> auth.login("alice", "old-password"));
                            });
            assertTrue(aliceStarted.await(5, TimeUnit.SECONDS));
            try {
                workers.submit(
                                () -> {
                                    var bob = auth.login("bob", "test-password");
                                    auth.logout(bob.token());
                                    assertFalse(tokens.hasLoginSessions(BOB));
                                    assertNotNull(auth.status(null));
                                    assertTrue(auth.canManageUsers(sender));
                                })
                        .get(5, TimeUnit.SECONDS);
                assertFalse(alice.isDone());
                verify(users, never()).authenticate("alice", "old-password");
            } finally {
                release.countDown();
            }
            change.get(5, TimeUnit.SECONDS);
            assertEquals(
                    AuthException.Kind.INVALID_CREDENTIALS, alice.get(5, TimeUnit.SECONDS).kind());
        } finally {
            release.countDown();
        }
    }

    @Test
    void synchronousCallbackCannotUpgradeSharedAccessToDeletion() {
        var locks = new AccountLocks();
        try (var _ = locks.account(ALICE)) {
            assertThrows(IllegalStateException.class, locks::exclusive);
            try (var _ = locks.account(ALICE)) {
                assertTrue(locks.heldByCurrentThread(ALICE));
            }
        }
        try (var _ = locks.exclusive()) {
            assertTrue(locks.exclusiveByCurrentThread());
        }
    }

    @Test
    void contendedCrossAccountCallbacksFailInsteadOfDeadlockingAndReleaseTheirLocks()
            throws Exception {
        var locks = new AccountLocks();
        var bothHeld = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var owner =
                    workers.submit(
                            () -> {
                                try (var _ = locks.account(BOB)) {
                                    bothHeld.countDown();
                                    assertTrue(release.await(5, TimeUnit.SECONDS));
                                }
                                return true;
                            });
            try (var _ = locks.account(ALICE)) {
                bothHeld.countDown();
                assertTrue(bothHeld.await(5, TimeUnit.SECONDS));
                assertThrows(IllegalStateException.class, () -> locks.account(ALICE, BOB));
                assertTrue(locks.heldByCurrentThread(ALICE));
                assertFalse(locks.heldByCurrentThread(BOB));
            } finally {
                release.countDown();
            }
            assertTrue(owner.get(5, TimeUnit.SECONDS));
            workers.submit(
                            () -> {
                                try (var _ = locks.account(BOB, ALICE)) {
                                    assertTrue(locks.heldByCurrentThread(ALICE));
                                    assertTrue(locks.heldByCurrentThread(BOB));
                                }
                            })
                    .get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
    }
}
