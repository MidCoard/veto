package top.focess.veto.vault;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.IntStream;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Process-local exclusion using 256 fixed account stripes and a global lifecycle gate. Different
 * accounts may share a stripe; database transactions still own persistence integrity.
 */
final class AccountLocks {
    private static final int STRIPE_COUNT = 256;
    private final @NonNull ReentrantReadWriteLock gate = new ReentrantReadWriteLock(true);
    private final @NonNull List<@NonNull ReentrantLock> stripes =
            IntStream.range(0, STRIPE_COUNT).mapToObj(ignored -> new ReentrantLock()).toList();
    private final @NonNull ThreadLocal<@Nullable Integer> held = new ThreadLocal<>();

    @NonNull AccountLockLease shared() {
        var lock = gate.readLock();
        lock.lock();
        return new AccountLockLease(held, List.of(lock), 0);
    }

    @NonNull AccountLockLease exclusive() {
        if (gate.getReadHoldCount() > 0 && !gate.isWriteLockedByCurrentThread())
            throw new IllegalStateException(
                    "Cannot start account deletion/setup inside an account callback");
        var lock = gate.writeLock();
        lock.lock();
        return new AccountLockLease(held, List.of(lock), 0);
    }

    @NonNull AccountLockLease account(@NonNull UUID @NonNull ... identities) {
        var acquired = new ArrayList<@NonNull Lock>();
        int accountCount = 0;
        var read = gate.readLock();
        read.lock();
        acquired.add(read);
        try {
            // Order actual locks, not UUIDs: different UUIDs can select the same stripe.
            for (int index :
                    Arrays.stream(identities)
                            .mapToInt(AccountLocks::stripe)
                            .distinct()
                            .sorted()
                            .toArray()) {
                var lock = stripes.get(index);
                // Inline plugin callbacks may reenter. Never wait on another owner's lock
                // while already holding an account lock: that could reverse the lock order.
                if (!lock.tryLock()) {
                    if (heldCount() > 0)
                        throw new IllegalStateException(
                                "Contended cross-account authentication callback");
                    lock.lock();
                }
                acquired.add(lock);
                accountCount++;
            }
            held.set(heldCount() + accountCount);
            return new AccountLockLease(held, acquired, accountCount);
        } catch (Throwable failure) {
            for (int i = acquired.size() - 1; i >= 0; i--) acquired.get(i).unlock();
            throw failure;
        }
    }

    private int heldCount() {
        var count = held.get();
        return count == null ? 0 : count;
    }

    private static int stripe(@NonNull UUID identity) {
        return Math.floorMod(identity.hashCode(), STRIPE_COUNT);
    }

    /** Whether this thread holds the stripe selected by the account, including collisions. */
    boolean heldByCurrentThread(@NonNull UUID identity) {
        return stripes.get(stripe(identity)).isHeldByCurrentThread();
    }

    boolean exclusiveByCurrentThread() {
        return gate.isWriteLockedByCurrentThread();
    }

    /** Same-thread lease that releases acquired account locks in reverse acquisition order. */
    static final class AccountLockLease implements AutoCloseable {
        private final @NonNull ThreadLocal<@Nullable Integer> held;
        private final @NonNull List<@NonNull Lock> acquired;
        private final int accountCount;

        private AccountLockLease(
                @NonNull ThreadLocal<@Nullable Integer> held,
                @NonNull List<@NonNull Lock> acquired,
                int accountCount) {
            this.held = held;
            this.acquired = acquired;
            this.accountCount = accountCount;
        }

        @Override
        public void close() {
            var count = held.get();
            int remaining = (count == null ? 0 : count) - accountCount;
            if (remaining == 0) held.remove();
            else held.set(remaining);
            for (int i = acquired.size() - 1; i >= 0; i--) acquired.get(i).unlock();
        }
    }
}
