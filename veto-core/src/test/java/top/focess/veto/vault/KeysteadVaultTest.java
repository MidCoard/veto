package top.focess.veto.vault;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.focess.veto.agent.tool.ToolDocs;

/**
 * Verifies {@link KeysteadVault} against the real keystead {@code OneFileVaultStore} crypto: signup
 * opens a handle, notes round-trip, logout/login reopens a persisted vault, upsert does not
 * duplicate, and a locked vault rejects operations.
 */
class KeysteadVaultTest {
    @Test
    void importedCredentialUseRequiresTheExactOwnerAndService(@TempDir @NonNull Path tempDir) {
        var vault = newVault(tempDir);
        try {
            vault.signup("alice", "p@ssw0rd!");
            String reference =
                    vault.createImportedCredential(
                            "alice",
                            "s_0123456789abcdef0123456789abcdef",
                            "github",
                            "Repository",
                            "synthetic-token");
            boolean[] invoked = {false};
            vault.withImportedCredential(
                    "alice",
                    reference,
                    "github",
                    value -> {
                        assertArrayEquals("synthetic-token".toCharArray(), value);
                        invoked[0] = true;
                    });
            assertTrue(invoked[0]);
            vault.signup("bob", "other-password");
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            vault.withImportedCredential(
                                    "bob",
                                    reference,
                                    "github",
                                    value -> fail("Wrong owner must not receive credential")));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            vault.withImportedCredential(
                                    "alice",
                                    reference,
                                    "other-service",
                                    value -> fail("Wrong service must not receive credential")));
            vault.logout("alice");
            assertThrows(
                    ToolDocs.nonNullClass(KeysteadVault.VaultLockedException.class),
                    () ->
                            vault.withImportedCredential(
                                    "alice",
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
        var scope = new SecretCandidateStore.Scope("alice", "session", "agent");
        try {
            vault.signup("alice", "p@ssw0rd!");
            String reference =
                    store.capture(scope, "source", "password=synthetic-token")
                            .candidates()
                            .getFirst()
                            .reference();
            var receipt = store.importOnce(scope, reference, "github", "Repository", vault);
            assertEquals(
                    receipt, store.importOnce(scope, reference, "github", "Repository", vault));
            assertEquals(1, vault.listTitles().size());
            assertEquals(
                    Optional.of("synthetic-token"), vault.readNoteBody("veto.import." + reference));
            assertEquals(
                    SecretCandidateStore.State.IMPORTED,
                    store.describe(scope, reference).orElseThrow().state());
            assertEquals(
                    "[SECRET_REF:" + reference + "]",
                    store.capture(scope, "repeat", "synthetic-token").text());
            var other = new SecretCandidateStore.Scope("alice", "session", "mate");
            assertThrows(
                    IllegalStateException.class,
                    () -> store.importOnce(other, reference, "github", "Repository", vault));
            store.closeOwner("alice");
            assertThrows(
                    IllegalStateException.class,
                    () -> store.importOnce(scope, reference, "github", "Repository", vault));
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
            vault.signup("alice", "p@ssw0rd!");
            UserContext.set("alice");
            vault.saveNote("Repository", "existing-value");
            String reference =
                    vault.createImportedCredential(
                            "alice", importId, "github", "Repository", "synthetic-token");
            assertEquals(
                    reference,
                    vault.createImportedCredential(
                            "alice", importId, "github", "Repository", "synthetic-token"));
            assertEquals(Optional.of("existing-value"), vault.readNoteBody("Repository"));
            assertEquals(2, vault.listTitles().size());
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            vault.createImportedCredential(
                                    "alice", importId, "github", "Changed", "synthetic-token"));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            vault.createImportedCredential(
                                    "alice", importId, "github", "Repository", "different-token"));
            assertThrows(
                    ToolDocs.nonNullClass(KeysteadVault.VaultLockedException.class),
                    () ->
                            vault.createImportedCredential(
                                    "bob", importId, "github", "Repository", "synthetic-token"));
            vault.logout("alice");
            vault.login("alice", "p@ssw0rd!");
            assertEquals(
                    reference,
                    vault.createImportedCredential(
                            "alice", importId, "github", "Repository", "synthetic-token"));
            assertEquals(2, vault.listTitles().size());
        } finally {
            vault.shutdown();
        }
    }

    @Test
    void explicitOwnerReadinessCannotBorrowTheOnlyUnlockedVault(@TempDir @NonNull Path tempDir) {
        var vault = newVault(tempDir);
        try {
            vault.signup("bob", "p@ssw0rd!");
            UserContext.set("bob");
            assertTrue(vault.isUnlocked());
            assertTrue(vault.isUnlocked("bob"));
            assertFalse(vault.isUnlocked("alice"));
            UserContext.clear();
            assertFalse(vault.isUnlocked("alice"));
            vault.logout("bob");
            assertFalse(vault.isUnlocked("bob"));
        } finally {
            vault.shutdown();
        }
    }

    private static @NonNull KeysteadVault newVault(@NonNull Path tempDir) {
        CredentialVaultConfiguration config = new CredentialVaultConfiguration();
        config.setVaultHome(tempDir.toString());
        return new KeysteadVault(config);
    }

    @Test
    void concurrentImportRetriesCreateOneEncryptedRecord(@TempDir @NonNull Path tempDir)
            throws Exception {
        var vault = newVault(tempDir);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            vault.signup("alice", "p@ssw0rd!");
            var start = new CountDownLatch(1);
            var results = new ArrayList<Future<String>>();
            for (int i = 0; i < 8; i++)
                results.add(
                        workers.submit(
                                () -> {
                                    start.await();
                                    return vault.createImportedCredential(
                                            "alice",
                                            "s_0123456789abcdef0123456789abcdef",
                                            "github",
                                            "Repository",
                                            "synthetic-token");
                                }));
            start.countDown();
            String reference = results.getFirst().get(5, TimeUnit.SECONDS);
            for (var result : results) assertEquals(reference, result.get(5, TimeUnit.SECONDS));
            UserContext.set("alice");
            assertEquals(1, vault.listTitles().size());
        } finally {
            vault.shutdown();
        }
    }

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    void signupAndRoundTrip(@TempDir @NonNull Path tempDir) {
        KeysteadVault vault = newVault(tempDir);
        vault.signup("alice", "p@ssw0rd!");

        vault.saveNote("pattern-coder", "sk-xxx");
        assertEquals(Optional.of("sk-xxx"), vault.readNoteBody("pattern-coder"));
        assertTrue(vault.listTitles().contains("pattern-coder"));

        assertTrue(vault.deleteNote("pattern-coder"));
        assertTrue(vault.readNoteBody("pattern-coder").isEmpty());
    }

    @Test
    void loginReopensPersistedVault(@TempDir @NonNull Path tempDir) {
        KeysteadVault vault = newVault(tempDir);
        vault.signup("alice", "p@ssw0rd!");
        vault.saveNote("pattern-coder", "sk-xxx");
        vault.logout("alice");

        // A fresh KeysteadVault instance (simulating a restart) reopens the same persisted vault.
        KeysteadVault reopened = newVault(tempDir);
        reopened.login("alice", "p@ssw0rd!");
        assertEquals(Optional.of("sk-xxx"), reopened.readNoteBody("pattern-coder"));
    }

    @Test
    void saveNoteUpsertDoesNotDuplicate(@TempDir @NonNull Path tempDir) {
        KeysteadVault vault = newVault(tempDir);
        vault.signup("alice", "p@ssw0rd!");
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
                ToolDocs.nonNullClass(KeysteadVault.VaultLockedException.class),
                () -> vault.readNoteBody("anything"));
        assertThrows(
                ToolDocs.nonNullClass(KeysteadVault.VaultLockedException.class),
                () -> vault.saveNote("k", "v"));
    }

    @Test
    void wrongPasswordFailsToOpen(@TempDir @NonNull Path tempDir) {
        KeysteadVault vault = newVault(tempDir);
        vault.signup("alice", "p@ssw0rd!");
        vault.logout("alice");

        KeysteadVault reopened = newVault(tempDir);
        assertThrows(
                ToolDocs.nonNullClass(Exception.class),
                () -> reopened.login("alice", "wrong-password"));
    }

    @Test
    void unlockedVaultDoesNotAuthenticateAnonymousRequest(@TempDir @NonNull Path tempDir) {
        KeysteadVault vault = newVault(tempDir);
        vault.signup("alice", "p@ssw0rd!");

        assertNull(vault.currentUser());
        assertEquals("alice", vault.currentUserOrOnlyUnlocked());

        UserContext.set("alice");
        assertEquals("alice", vault.currentUser());
    }
}
