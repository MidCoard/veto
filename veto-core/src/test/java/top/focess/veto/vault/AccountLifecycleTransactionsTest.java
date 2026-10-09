package top.focess.veto.vault;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.focess.veto.agent.continuation.RequestContinuationStore;
import top.focess.veto.agent.intercept.HitlRecordRepository;
import top.focess.veto.command.PromptHandler;
import top.focess.veto.controller.AuthController;
import top.focess.veto.controller.dto.AuthCredentials;
import top.focess.veto.event.EventManager;
import top.focess.veto.integration.plugins.PluginDataCleanup;
import top.focess.veto.integration.plugins.storage.ScopedPluginStorage;
import top.focess.veto.model.AgentInstanceRepository;
import top.focess.veto.model.AgentPatternRepository;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.security.SignupPolicy;
import top.focess.veto.terminal.IpcServer;

/**
 * Exercises real encrypted files and Spring commit/rollback callbacks with an isolated row store.
 */
class AccountLifecycleTransactionsTest {
    @Test
    void bootstrapCommitFailureLeavesSetupRetryable(@TempDir @NonNull Path directory) {
        var fixture = new Fixture(directory);
        var controller =
                new AuthController(
                        new AuthService(
                                fixture.users,
                                fixture.logins,
                                fixture.vault,
                                fixture.auth,
                                fixture.accounts,
                                new SignupPolicy("public", "LOCAL")));
        fixture.transactions.failCommit = true;
        fixture.transactions.beforeCommit = () -> assertTrue(Files.exists(fixture.store("owner")));

        assertEquals(
                500,
                controller
                        .setup(new AuthCredentials("owner", "test-password"))
                        .getStatusCode()
                        .value());
        assertFalse(fixture.users.anyUserExists());
        assertFalse(Files.exists(fixture.directory.resolve("keystead/owner")));
        assertEquals(0, fixture.logins.activeLoginSessionCount());
        assertFalse(fixture.vault.isUnlocked());

        fixture.transactions.failCommit = false;
        assertEquals(
                200,
                controller
                        .setup(new AuthCredentials("owner", "test-password"))
                        .getStatusCode()
                        .value());
        assertEquals(1, fixture.users.adminCount());
        assertEquals(1, fixture.logins.activeLoginSessionCount());
        fixture.vault.logoutAll();
    }

    @Test
    void provisioningCannotOverwriteAnExistingStore(@TempDir @NonNull Path directory)
            throws Exception {
        var fixture = new Fixture(directory);
        var oldStore = fixture.store("owner");
        Files.createDirectories(fixture.directory.resolve("keystead/owner"));
        Files.writeString(oldStore, "existing-encrypted-data");

        assertThrows(
                UncheckedIOException.class,
                () -> fixture.accounts.create("owner", "test-password", UserRegistry.Role.USER));
        assertTrue(fixture.users.findByUsername("owner").isEmpty());
        assertEquals("existing-encrypted-data", Files.readString(oldStore));
    }

    @Test
    void failedDeletePreservesRealSecretsAndSuccessfulDeleteRunsAfterCommit(
            @TempDir @NonNull Path directory) {
        var fixture = new Fixture(directory);
        var user = fixture.accounts.create("owner", "test-password", UserRegistry.Role.USER);
        fixture.auth.login(fixture.users.findByUsername("owner").orElseThrow(), "test-password");
        try (var scope = ExecutionSecurity.open(user.getUserId())) {
            assertNotNull(scope);
            fixture.vault.saveNote("credential", "kept-after-rollback");
        }
        fixture.transactions.beforeCommit = () -> assertTrue(Files.exists(fixture.store("owner")));
        fixture.transactions.failCommit = true;

        assertThrows(
                TransactionSystemException.class,
                () -> fixture.accounts.deleteUser(user.getUserId()));
        assertTrue(fixture.users.findByUserId(user.getUserId()).isPresent());
        fixture.auth.login(fixture.users.findByUsername("owner").orElseThrow(), "test-password");
        try (var scope = ExecutionSecurity.open(user.getUserId())) {
            assertNotNull(scope);
            assertEquals(
                    "kept-after-rollback", fixture.vault.readNoteBody("credential").orElseThrow());
        }

        fixture.transactions.failCommit = false;
        fixture.accounts.deleteUser(user.getUserId());
        assertTrue(fixture.users.findByUserId(user.getUserId()).isEmpty());
        assertFalse(Files.exists(fixture.directory.resolve("keystead/owner")));
    }

    @Test
    void passwordMigrationPreservesSecretsAndIdsAndRevokesTokens(@TempDir @NonNull Path directory) {
        var fixture = new Fixture(directory);
        var user = fixture.accounts.create("owner", "test-password", UserRegistry.Role.USER);
        var token = fixture.logins.createLoginSession(user.getUserId(), "owner");
        fixture.auth.login(fixture.users.findByUsername("owner").orElseThrow(), "test-password");
        try (var scope = ExecutionSecurity.open(user.getUserId())) {
            assertNotNull(scope);
            fixture.vault.saveNote("credential", "unchanged-secret");
            var importedReference =
                    fixture.vault.createSecureNoteIfAbsent(
                            user.getUserId(),
                            "veto.import.s_0123456789abcdef0123456789abcdef",
                            Map.of(
                                    "veto.import.id",
                                    "s_0123456789abcdef0123456789abcdef",
                                    "veto.import.service",
                                    "github"),
                            "preserved-imported-token");
            var ids = fixture.vault.currentHandle().listSecrets();
            fixture.accounts.setPassword(user.getUserId(), "new-password");
            assertFalse(fixture.vault.isUnlocked(user.getUserId()));
            assertTrue(fixture.logins.validateToken(token).isEmpty());
            assertTrue(fixture.users.authenticate("owner", "test-password").isEmpty());
            assertTrue(fixture.users.authenticate("owner", "new-password").isPresent());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.vault.login("owner", "test-password"));
            fixture.auth.login(fixture.users.findByUsername("owner").orElseThrow(), "new-password");
            assertEquals(ids, fixture.vault.currentHandle().listSecrets());
            assertEquals(
                    "unchanged-secret", fixture.vault.readNoteBody("credential").orElseThrow());
            fixture.vault.withImportedCredential(
                    user.getUserId(),
                    importedReference,
                    "github",
                    value -> assertArrayEquals("preserved-imported-token".toCharArray(), value));
        } finally {
            fixture.vault.logoutAll();
        }
        assertFalse(
                Files.exists(
                        fixture.store("owner").resolveSibling("vault.password-previous.keystead")));
    }

    @Test
    void uncertainPasswordCommitRevokesTokensAndRetainsLoginRecovery(
            @TempDir @NonNull Path directory) {
        var fixture = new Fixture(directory);
        var user = fixture.accounts.create("owner", "old-password", UserRegistry.Role.USER);
        var token = fixture.logins.createLoginSession(user.getUserId(), "owner");
        fixture.auth.login(fixture.users.findByUsername("owner").orElseThrow(), "old-password");
        try (var scope = ExecutionSecurity.open(user.getUserId())) {
            assertNotNull(scope);
            fixture.vault.saveNote("credential", "uncertain-commit-secret");
            fixture.transactions.setRollbackOnCommitFailure(false);
            fixture.transactions.failCommit = true;
            assertThrows(
                    TransactionSystemException.class,
                    () -> fixture.accounts.setPassword(user.getUserId(), "new-password"));
            assertTrue(fixture.logins.validateToken(token).isEmpty());
            assertTrue(
                    Files.exists(
                            fixture.store("owner")
                                    .resolveSibling("vault.password-previous.keystead")));
            assertTrue(fixture.users.authenticate("owner", "new-password").isPresent());
            fixture.auth.login(fixture.users.findByUsername("owner").orElseThrow(), "new-password");
            assertEquals(
                    "uncertain-commit-secret",
                    fixture.vault.readNoteBody("credential").orElseThrow());
        } finally {
            fixture.vault.logoutAll();
        }
    }

    @Test
    void passwordCommitFailureRestoresOldPasswordAndVault(@TempDir @NonNull Path directory) {
        var fixture = new Fixture(directory);
        var user = fixture.accounts.create("owner", "old-password", UserRegistry.Role.USER);
        fixture.auth.login(fixture.users.findByUsername("owner").orElseThrow(), "old-password");
        try (var scope = ExecutionSecurity.open(user.getUserId())) {
            assertNotNull(scope);
            fixture.vault.saveNote("credential", "rollback-secret");
            fixture.transactions.failCommit = true;
            assertThrows(
                    TransactionSystemException.class,
                    () -> fixture.accounts.setPassword(user.getUserId(), "new-password"));
            assertTrue(fixture.users.authenticate("owner", "old-password").isPresent());
            assertTrue(fixture.users.authenticate("owner", "new-password").isEmpty());
            fixture.auth.login(fixture.users.findByUsername("owner").orElseThrow(), "old-password");
            assertEquals("rollback-secret", fixture.vault.readNoteBody("credential").orElseThrow());
        } finally {
            fixture.vault.logoutAll();
        }
    }

    @Test
    void interruptedPasswordSwapRecoversUsingThePersistedAccountPassword(
            @TempDir @NonNull Path directory) {
        var fixture = new Fixture(directory);
        var user = fixture.accounts.create("owner", "old-password", UserRegistry.Role.USER);
        fixture.auth.login(fixture.users.findByUsername("owner").orElseThrow(), "old-password");
        try (var scope = ExecutionSecurity.open(user.getUserId())) {
            assertNotNull(scope);
            fixture.vault.saveNote("credential", "crash-preserved-secret");
            // Simulate process interruption after the file swap, before the account hash commits.
            TransactionSynchronizationManager.initSynchronization();
            try {
                fixture.vault.changePassword(user.getUserId(), "uncommitted-password");
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
            }
            var config = new CredentialVaultConfiguration();
            config.setVaultHome(directory.toString());
            var restarted = new KeysteadVault(config, fixture.users);
            try {
                restarted.login("owner", "old-password");
                assertEquals(
                        "crash-preserved-secret",
                        restarted.readNoteBody("credential").orElseThrow());
                assertTrue(fixture.users.authenticate("owner", "uncommitted-password").isEmpty());
            } finally {
                restarted.logoutAll();
            }
        } finally {
            fixture.vault.logoutAll();
        }
    }

    @Test
    void lockedVaultResetCannotAlterThePassword(@TempDir @NonNull Path directory) {
        var fixture = new Fixture(directory);
        var user = fixture.accounts.create("owner", "old-password", UserRegistry.Role.USER);
        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.accounts.setPassword(user.getUserId(), "new-password"));
        assertTrue(fixture.users.authenticate("owner", "old-password").isPresent());
        assertTrue(fixture.users.authenticate("owner", "new-password").isEmpty());
        fixture.auth.login(fixture.users.findByUsername("owner").orElseThrow(), "old-password");
        fixture.vault.logoutAll();
    }

    private static final class Fixture {
        private final @NonNull UserRegistry users;
        private final @NonNull KeysteadVault vault;
        private final @NonNull LoginSessionManager logins = new LoginSessionManager();
        private final @NonNull AuthLifecycleManager auth;
        private final @NonNull UserAdminService accounts;
        private final @NonNull RowsTransactions transactions;
        private final @NonNull Path directory;

        private Fixture(@NonNull Path directory) {
            this.directory = directory;
            Map<@NonNull UUID, @NonNull UserEntity> rows = new HashMap<>();
            var repository = mock(UserRepository.class);
            when(repository.findById(any()))
                    .thenAnswer(call -> Optional.ofNullable(rows.get(call.getArgument(0))));
            when(repository.findByUsername(anyString()))
                    .thenAnswer(
                            call ->
                                    rows.values().stream()
                                            .filter(
                                                    user ->
                                                            user.getUsername()
                                                                    .equals(call.getArgument(0)))
                                            .findFirst());
            when(repository.existsByUsername(anyString()))
                    .thenAnswer(
                            call ->
                                    rows.values().stream()
                                            .anyMatch(
                                                    user ->
                                                            user.getUsername()
                                                                    .equals(call.getArgument(0))));
            when(repository.count()).thenAnswer(call -> (long) rows.size());
            when(repository.countByRole(anyString()))
                    .thenAnswer(
                            call ->
                                    rows.values().stream()
                                            .filter(
                                                    user ->
                                                            user.getRole()
                                                                    .equals(call.getArgument(0)))
                                            .count());
            when(repository.saveAndFlush(any(UserEntity.class)))
                    .thenAnswer(
                            call -> {
                                UserEntity user = call.getArgument(0);
                                if (user == null) throw new AssertionError("Missing account");
                                rows.put(user.getUserId(), user);
                                return user;
                            });
            when(repository.save(any(UserEntity.class)))
                    .thenAnswer(
                            call -> {
                                UserEntity user = call.getArgument(0);
                                if (user == null) throw new AssertionError("Missing account");
                                rows.put(user.getUserId(), user);
                                return user;
                            });
            doAnswer(
                            call -> {
                                UserEntity user = call.getArgument(0);
                                if (user == null) throw new AssertionError("Missing account");
                                return rows.remove(user.getUserId());
                            })
                    .when(repository)
                    .delete(any(UserEntity.class));
            users = new UserRegistry(repository);
            transactions = new RowsTransactions(rows);
            var config = new CredentialVaultConfiguration();
            config.setVaultHome(directory.toString());
            vault = new KeysteadVault(config, users);
            var events = mock(EventManager.class);
            auth =
                    new AuthLifecycleManager(
                            vault,
                            mock(PromptHandler.class),
                            events,
                            logins,
                            new StaticListableBeanFactory().getBeanProvider(IpcServer.class));
            accounts =
                    new UserAdminService(
                            users,
                            mock(AgentPatternRepository.class),
                            mock(SessionRepository.class),
                            mock(AgentInstanceRepository.class),
                            vault,
                            auth,
                            events,
                            mock(PluginDataCleanup.class),
                            mock(ScopedPluginStorage.class),
                            mock(HitlRecordRepository.class),
                            mock(RequestContinuationStore.class),
                            transactions);
        }

        private @NonNull Path store(@NonNull String username) {
            return directory.resolve("keystead").resolve(username).resolve("vault.keystead");
        }
    }

    private static final class RowsTransactions extends AbstractPlatformTransactionManager {
        private static final long serialVersionUID = 1L;
        private final @NonNull Map<@NonNull UUID, @NonNull UserEntity> rows;
        private boolean failCommit;
        private @NonNull Runnable beforeCommit = () -> {};

        private RowsTransactions(@NonNull Map<@NonNull UUID, @NonNull UserEntity> rows) {
            this.rows = rows;
            setRollbackOnCommitFailure(true);
        }

        @Override
        protected @NonNull Object doGetTransaction() {
            Map<@NonNull UUID, @NonNull PasswordMaterial> passwords = new HashMap<>();
            rows.forEach(
                    (id, user) ->
                            passwords.put(
                                    id,
                                    new PasswordMaterial(
                                            user.getPasswordHash().clone(),
                                            user.getPasswordSalt().clone())));
            return new Snapshot(new HashMap<>(rows), passwords);
        }

        @Override
        protected void doBegin(
                @NonNull Object transaction, @NonNull TransactionDefinition definition) {}

        @Override
        protected void doCommit(@NonNull DefaultTransactionStatus status) {
            beforeCommit.run();
            if (failCommit) throw new TransactionSystemException("Injected account commit failure");
        }

        @Override
        protected void doRollback(@NonNull DefaultTransactionStatus status) {
            if (!(status.getTransaction() instanceof Snapshot snapshot))
                throw new AssertionError("Missing snapshot");
            rows.clear();
            rows.putAll(snapshot.rows());
            rows.forEach(
                    (id, user) -> {
                        var saved = snapshot.passwords().get(id);
                        if (saved == null) throw new AssertionError("Missing password snapshot");
                        ReflectionTestUtils.setField(user, "passwordHash", saved.hash());
                        ReflectionTestUtils.setField(user, "passwordSalt", saved.salt());
                    });
        }

        private record Snapshot(
                @NonNull Map<@NonNull UUID, @NonNull UserEntity> rows,
                @NonNull Map<@NonNull UUID, @NonNull PasswordMaterial> passwords) {}

        private record PasswordMaterial(byte @NonNull [] hash, byte @NonNull [] salt) {}
    }
}
