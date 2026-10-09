package top.focess.veto.vault;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest(showSql = false)
@Import(UserRegistry.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AccountPersistenceConcurrencyTest {
    private final @NonNull UserRegistry users;
    private final @NonNull UserRepository repository;

    @Autowired
    AccountPersistenceConcurrencyTest(
            @NonNull UserRegistry users, @NonNull UserRepository repository) {
        this.users = users;
        this.repository = repository;
    }

    @Test
    void databaseRejectsDuplicateUsernameWithoutAnApplicationPrecheck() {
        var original = users.create("constraint-account", "test-password", UserRegistry.Role.USER);
        try {
            var duplicate =
                    new UserEntity(
                            "constraint-account",
                            new byte[] {1},
                            new byte[] {2},
                            UserRegistry.Role.USER,
                            Instant.now());
            assertThrows(
                    DataIntegrityViolationException.class,
                    () -> repository.saveAndFlush(duplicate));
            assertEquals(
                    original.getUserId(),
                    users.findByUsername("constraint-account").orElseThrow().getUserId());
        } finally {
            users.deleteByUserId(original.getUserId());
        }
    }

    @Test
    void concurrentRegistrationHasExactlyOneWinner() throws Exception {
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> registerAfter(start));
            var second = workers.submit(() -> registerAfter(start));
            start.countDown();
            assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertTrue(users.findByUsername("same-account").isPresent());
        } finally {
            users.findByUsername("same-account")
                    .ifPresent(account -> users.deleteByUserId(account.getUserId()));
        }
    }

    private boolean registerAfter(@NonNull CountDownLatch start) throws InterruptedException {
        assertTrue(start.await(5, TimeUnit.SECONDS));
        try {
            users.create("same-account", "test-password", UserRegistry.Role.USER);
            return true;
        } catch (IllegalArgumentException duplicate) {
            assertEquals("User 'same-account' already exists", duplicate.getMessage());
            return false;
        }
    }
}
