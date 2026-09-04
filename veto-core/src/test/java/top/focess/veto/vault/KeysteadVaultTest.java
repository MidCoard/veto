package top.focess.veto.vault;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
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

    private static @NonNull KeysteadVault newVault(@NonNull Path tempDir) {
        CredentialVaultConfiguration config = new CredentialVaultConfiguration();
        config.setVaultHome(tempDir.toString());
        return new KeysteadVault(config);
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
