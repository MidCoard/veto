package top.focess.veto.vault;

import static org.junit.jupiter.api.Assertions.*;
import static top.focess.veto.vault.TestUsers.ALICE;
import static top.focess.veto.vault.TestUsers.BOB;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.core.context.SecurityContextHolder;
import top.focess.veto.api.credentials.VaultAccess;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.secret.references.SecretCandidateStore;

/**
 * Verifies {@link KeysteadVault} against the real keystead {@code OneFileVaultStore} crypto: signup
 * opens a handle, notes round-trip, logout/login reopens a persisted vault, upsert does not
 * duplicate, and a locked vault rejects operations.
 */
class KeysteadVaultTest {
    @Test
    void importedServicesAreBoundedGenericIdentifiers(@TempDir @NonNull Path tempDir) {
        var vault = newVault(tempDir);
        try {
            assertEquals(ALICE, vault.signup("alice", "password"));
            var ref =
                    importedNote(
                            vault,
                            ALICE,
                            "s_0123456789abcdef0123456789abcdef",
                            "custom-service.v2",
                            "Label",
                            "synthetic-token");
            vault.withImportedCredential(
                    ALICE,
                    ref,
                    "custom-service.v2",
                    value -> assertEquals("synthetic-token", new String(value)));
            String invalidServiceRef =
                    importedNote(
                            vault,
                            ALICE,
                            "s_1123456789abcdef0123456789abcdef",
                            "https://bad",
                            "Label",
                            "token");
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            vault.withImportedCredential(
                                    ALICE, invalidServiceRef, "https://bad", value -> fail()));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            vault.withImportedCredential(
                                    ALICE, ref, "other", value -> fail("Service mismatch")));
        } finally {
            vault.shutdown();
        }
    }

    @Test
    void importedCredentialUseRequiresTheExactOwnerAndService(@TempDir @NonNull Path tempDir) {
        var vault = newVault(tempDir);
        try {
            assertEquals(ALICE, vault.signup("alice", "p@ssw0rd!"));
            String reference =
                    importedNote(
                            vault,
                            ALICE,
                            "s_0123456789abcdef0123456789abcdef",
                            "github",
                            "Repository",
                            "synthetic-token");
            boolean[] invoked = {false};
            vault.withImportedCredential(
                    ALICE,
                    reference,
                    "github",
                    value -> {
                        assertArrayEquals("synthetic-token".toCharArray(), value);
                        invoked[0] = true;
                    });
            assertTrue(invoked[0]);
            assertEquals(BOB, vault.signup("bob", "other-password"));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            vault.withImportedCredential(
                                    BOB,
                                    reference,
                                    "github",
                                    value -> fail("Wrong owner must not receive credential")));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            vault.withImportedCredential(
                                    ALICE,
                                    reference,
                                    "other-service",
                                    value -> fail("Wrong service must not receive credential")));
            vault.logout(ALICE);
            assertThrows(
                    KeysteadVault.VaultLockedException.class,
                    () ->
                            vault.withImportedCredential(
                                    ALICE,
                                    reference,
                                    "github",
                                    value -> fail("Locked owner must not use Bob's handle")));
        } finally {
            vault.shutdown();
        }
    }

    @Test
    void capturedCandidateImportsIntoRealVaultAndKeepsItsStableReference(
            @TempDir @NonNull Path tempDir) {
        var vault = newVault(tempDir);
        var store = new SecretCandidateStore();
        var writer = credentialWriter(vault);
        var scope = new Scope.AgentScope(ALICE, "session", "agent");
        try {
            assertEquals(ALICE, vault.signup("alice", "p@ssw0rd!"));
            String reference =
                    store.capture(scope, "source", "password=synthetic-token")
                            .candidates()
                            .getFirst()
                            .reference();
            var receipt = store.importOnce(scope, reference, "github", "Repository", writer);
            assertEquals(
                    receipt, store.importOnce(scope, reference, "github", "Repository", writer));
            assertEquals(1, vault.listTitles().size());
            assertEquals(
                    Optional.of("synthetic-token"), vault.readNoteBody("veto.import." + reference));
            assertEquals(
                    SecretCandidateStore.State.IMPORTED,
                    store.describe(scope, reference).orElseThrow().state());
            assertEquals(
                    "[SECRET_REF:" + reference + "]",
                    store.capture(scope, "repeat", "synthetic-token").text());
            var other = new Scope.AgentScope(ALICE, "session", "mate");
            assertThrows(
                    IllegalStateException.class,
                    () -> store.importOnce(other, reference, "github", "Repository", writer));
            store.closeUser(ALICE);
            assertThrows(
                    IllegalStateException.class,
                    () -> store.importOnce(scope, reference, "github", "Repository", writer));
            assertEquals(1, vault.listTitles().size());
        } finally {
            vault.shutdown();
        }
    }

    @Test
    void importedCredentialIsOwnerBoundAndIdempotentWithoutReplacingHumanLabel(
            @TempDir @NonNull Path tempDir) {
        var vault = newVault(tempDir);
        String importId = "s_0123456789abcdef0123456789abcdef";
        try {
            assertEquals(ALICE, vault.signup("alice", "p@ssw0rd!"));
            SecurityContextHolder.setContext(ExecutionSecurity.contextFor(ALICE));
            vault.saveNote("Repository", "existing-value");
            String reference =
                    importedNote(vault, ALICE, importId, "github", "Repository", "synthetic-token");
            assertEquals(
                    reference,
                    importedNote(
                            vault, ALICE, importId, "github", "Repository", "synthetic-token"));
            assertEquals(Optional.of("existing-value"), vault.readNoteBody("Repository"));
            assertEquals(2, vault.listTitles().size());
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            importedNote(
                                    vault,
                                    ALICE,
                                    importId,
                                    "github",
                                    "Changed",
                                    "synthetic-token"));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            importedNote(
                                    vault,
                                    ALICE,
                                    importId,
                                    "github",
                                    "Repository",
                                    "different-token"));
            assertThrows(
                    KeysteadVault.VaultLockedException.class,
                    () ->
                            importedNote(
                                    vault,
                                    BOB,
                                    importId,
                                    "github",
                                    "Repository",
                                    "synthetic-token"));
            vault.logout(ALICE);
            assertEquals(ALICE, vault.login("alice", "p@ssw0rd!"));
            assertEquals(
                    reference,
                    importedNote(
                            vault, ALICE, importId, "github", "Repository", "synthetic-token"));
            assertEquals(2, vault.listTitles().size());
        } finally {
            vault.shutdown();
        }
    }

    @Test
    void explicitOwnerReadinessCannotBorrowTheOnlyUnlockedVault(@TempDir @NonNull Path tempDir) {
        var vault = newVault(tempDir);
        try {
            assertEquals(BOB, vault.signup("bob", "p@ssw0rd!"));
            SecurityContextHolder.setContext(ExecutionSecurity.contextFor(BOB));
            assertTrue(vault.isUnlocked());
            assertTrue(vault.isUnlocked(BOB));
            assertFalse(vault.isUnlocked(ALICE));
            SecurityContextHolder.clearContext();
            assertFalse(vault.isUnlocked(ALICE));
            vault.logout(BOB);
            assertFalse(vault.isUnlocked(BOB));
        } finally {
            vault.shutdown();
        }
    }

    private static @NonNull KeysteadVault newVault(@NonNull Path tempDir) {
        CredentialVaultConfiguration config = new CredentialVaultConfiguration();
        config.setVaultHome(tempDir.toString());
        var users = TestUsers.registry();
        return new KeysteadVault(config, users);
    }

    private static @NonNull String importedNote(
            @NonNull KeysteadVault vault,
            @NonNull UUID userId,
            @NonNull String reference,
            @NonNull String service,
            @NonNull String label,
            @NonNull String value) {
        return vault.createSecureNoteIfAbsent(
                userId,
                "veto.import." + reference,
                Map.of(
                        "veto.import.id", reference,
                        "veto.import.service", service,
                        "veto.import.label", label),
                value);
    }

    private static VaultAccess.@NonNull Handle credentialWriter(@NonNull KeysteadVault vault) {
        return new VaultAccess.Handle() {
            @Override
            public Scope.@NonNull AgentScope scope() {
                return new Scope.AgentScope(ALICE, "session", "agent");
            }

            @Override
            public boolean isUnlocked() {
                return vault.isUnlocked(ALICE);
            }

            @Override
            public @NonNull String createSecureNote(
                    @NonNull String title,
                    @NonNull Map<@NonNull String, @NonNull String> attributes,
                    @NonNull String value) {
                return vault.createSecureNoteIfAbsent(ALICE, title, attributes, value);
            }
        };
    }

    @Test
    void concurrentImportRetriesCreateOneEncryptedRecord(@TempDir @NonNull Path tempDir)
            throws Exception {
        var vault = newVault(tempDir);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            assertEquals(ALICE, vault.signup("alice", "p@ssw0rd!"));
            var start = new CountDownLatch(1);
            var results = new ArrayList<Future<String>>();
            for (int i = 0; i < 8; i++)
                results.add(
                        workers.submit(
                                () -> {
                                    start.await();
                                    return importedNote(
                                            vault,
                                            ALICE,
                                            "s_0123456789abcdef0123456789abcdef",
                                            "github",
                                            "Repository",
                                            "synthetic-token");
                                }));
            start.countDown();
            String reference = results.getFirst().get(5, TimeUnit.SECONDS);
            for (var result : results) assertEquals(reference, result.get(5, TimeUnit.SECONDS));
            SecurityContextHolder.setContext(ExecutionSecurity.contextFor(ALICE));
            assertEquals(1, vault.listTitles().size());
        } finally {
            vault.shutdown();
        }
    }

    @AfterEach
    void clearCurrentUser() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void signupAndRoundTrip(@TempDir @NonNull Path tempDir) {
        KeysteadVault vault = newVault(tempDir);
        assertEquals(ALICE, vault.signup("alice", "p@ssw0rd!"));

        vault.saveNote("pattern-coder", "sk-xxx");
        assertEquals(Optional.of("sk-xxx"), vault.readNoteBody("pattern-coder"));
        assertTrue(vault.listTitles().contains("pattern-coder"));

        assertTrue(vault.deleteNote("pattern-coder"));
        assertTrue(vault.readNoteBody("pattern-coder").isEmpty());
    }

    @Test
    void loginReopensPersistedVault(@TempDir @NonNull Path tempDir) {
        KeysteadVault vault = newVault(tempDir);
        assertEquals(ALICE, vault.signup("alice", "p@ssw0rd!"));
        vault.saveNote("pattern-coder", "sk-xxx");
        vault.logout(ALICE);

        // A fresh KeysteadVault instance (simulating a restart) reopens the same persisted vault.
        KeysteadVault reopened = newVault(tempDir);
        assertEquals(ALICE, reopened.login("alice", "p@ssw0rd!"));
        assertEquals(Optional.of("sk-xxx"), reopened.readNoteBody("pattern-coder"));
    }

    @Test
    void saveNoteUpsertDoesNotDuplicate(@TempDir @NonNull Path tempDir) {
        KeysteadVault vault = newVault(tempDir);
        assertEquals(ALICE, vault.signup("alice", "p@ssw0rd!"));
        vault.saveNote("pattern-coder", "sk-old");
        vault.saveNote("pattern-coder", "sk-new");

        assertEquals(Optional.of("sk-new"), vault.readNoteBody("pattern-coder"));
        Set<String> titles = vault.listTitles();
        assertEquals(
                1, titles.stream().filter("pattern-coder"::equals).count(), "no duplicate titles");
    }

    @Test
    void lockedVaultRejectsOperations(@TempDir @NonNull Path tempDir) {
        KeysteadVault vault = newVault(tempDir);
        assertThrows(
                KeysteadVault.VaultLockedException.class, () -> vault.readNoteBody("anything"));
        assertThrows(KeysteadVault.VaultLockedException.class, () -> vault.saveNote("k", "v"));
    }

    @Test
    void wrongPasswordFailsToOpen(@TempDir @NonNull Path tempDir) {
        KeysteadVault vault = newVault(tempDir);
        assertEquals(ALICE, vault.signup("alice", "p@ssw0rd!"));
        vault.logout(ALICE);

        KeysteadVault reopened = newVault(tempDir);
        assertThrows(
                Exception.class,
                () -> assertEquals(ALICE, reopened.login("alice", "wrong-password")));
    }

    @Test
    void unlockedVaultDoesNotAuthenticateAnonymousRequest(@TempDir @NonNull Path tempDir) {
        KeysteadVault vault = newVault(tempDir);
        assertEquals(ALICE, vault.signup("alice", "p@ssw0rd!"));

        assertNull(vault.currentUser());
        assertEquals(ALICE, vault.currentUserOrOnlyUnlocked());

        SecurityContextHolder.setContext(ExecutionSecurity.contextFor(ALICE));
        assertEquals(ALICE, vault.currentUser());
    }
}
