package top.focess.veto.vault;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class AccountLocksTest {
    @Test
    void collidingAccountsSerializeWhileAnotherStripeCanProceed() throws Exception {
        var locks = new AccountLocks();
        var first = new UUID(0, 0);
        var collision = new UUID(0, 256);
        var other = new UUID(0, 1);
        var negativeHash = new UUID(0, 0x80000001L);
        assertTrue(negativeHash.hashCode() < 0);
        var started = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            Future<Boolean> waiting;
            try (var _ = locks.account(first, collision, first)) {
                assertTrue(locks.heldByCurrentThread(first));
                assertTrue(locks.heldByCurrentThread(collision));
                assertFalse(locks.heldByCurrentThread(other));
                waiting =
                        workers.submit(
                                () -> {
                                    started.countDown();
                                    try (var _ = locks.account(collision)) {
                                        return locks.heldByCurrentThread(collision);
                                    }
                                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> waiting.get(200, TimeUnit.MILLISECONDS));
                assertTrue(
                        workers.submit(
                                        () -> {
                                            try (var _ = locks.account(other, negativeHash)) {
                                                return locks.heldByCurrentThread(other);
                                            }
                                        })
                                .get(5, TimeUnit.SECONDS));
            }
            assertTrue(waiting.get(5, TimeUnit.SECONDS));
            assertFalse(locks.heldByCurrentThread(first));
            assertFalse(locks.heldByCurrentThread(collision));
        }
    }

    @Test
    void multipleAccountsAcquireStripeOrderInsteadOfUuidOrder() throws Exception {
        var locks = new AccountLocks();
        var stripeZero = new UUID(0, 0);
        var stripeOne = new UUID(0, 1);
        var zeroAlias = new UUID(0, 256);
        // UUID ordering puts stripeOne before zeroAlias; stripe ordering must do the opposite.
        assertTrue(stripeOne.compareTo(zeroAlias) < 0);
        var started = new CountDownLatch(1);
        var thread = new AtomicReference<@Nullable Thread>();
        try (var workers = Executors.newFixedThreadPool(2)) {
            Future<Boolean> waiting;
            try (var _ = locks.account(stripeZero)) {
                waiting =
                        workers.submit(
                                () -> {
                                    thread.set(Thread.currentThread());
                                    started.countDown();
                                    try (var _ = locks.account(stripeOne, zeroAlias)) {
                                        return locks.heldByCurrentThread(stripeOne)
                                                && locks.heldByCurrentThread(zeroAlias);
                                    }
                                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                var waiter = thread.get();
                if (waiter == null) throw new AssertionError("Missing acquisition thread");
                awaitWaiting(waiter);
                // While blocked on stripe zero, the waiter must not already own stripe one.
                assertTrue(
                        workers.submit(
                                        () -> {
                                            try (var _ = locks.account(stripeOne)) {
                                                return locks.heldByCurrentThread(stripeOne);
                                            }
                                        })
                                .get(5, TimeUnit.SECONDS));
                assertFalse(waiting.isDone());
            }
            assertTrue(waiting.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void exclusiveAccessWaitsForAnotherThreadsReaderWithoutThrowing() throws Exception {
        var locks = new AccountLocks();
        var started = new CountDownLatch(1);
        try (var workers = Executors.newSingleThreadExecutor()) {
            Future<Boolean> exclusive;
            try (var _ = locks.shared()) {
                exclusive =
                        workers.submit(
                                () -> {
                                    started.countDown();
                                    try (var _ = locks.exclusive()) {
                                        return locks.exclusiveByCurrentThread();
                                    }
                                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertThrows(
                        TimeoutException.class, () -> exclusive.get(200, TimeUnit.MILLISECONDS));
            }
            assertTrue(exclusive.get(5, TimeUnit.SECONDS));
        }
    }

    private static void awaitWaiting(@NonNull Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.getState() != Thread.State.WAITING) {
            if (thread.getState() == Thread.State.TERMINATED || System.nanoTime() >= deadline)
                throw new AssertionError("Acquisition did not wait for the held stripe");
            Thread.sleep(1);
        }
    }
}
