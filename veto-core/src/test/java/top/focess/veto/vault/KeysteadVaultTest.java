package top.focess.veto.vault;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies {@link KeysteadVault} against the real keystead {@code FileVaultStore} crypto: signup
 * opens a handle, notes round-trip, logout/login reopens a persisted vault, upsert does not
 * duplicate, and a locked vault rejects operations.
 */
class KeysteadVaultTest {

    @TempDir Path tempDir;

    private KeysteadVault newVault() {
        CredentialVaultConfiguration config = new CredentialVaultConfiguration();
        config.setVaultHome(tempDir.toString());
        return new KeysteadVault(config);
    }

    @Test
    void signupAndRoundTrip() {
        KeysteadVault vault = newVault();
        vault.signup("alice", "p@ssw0rd!");

        vault.saveNote("pattern-coder", "sk-xxx");
        assertEquals(Optional.of("sk-xxx"), vault.readNoteBody("pattern-coder"));
        assertTrue(vault.listTitles().contains("pattern-coder"));

        assertTrue(vault.deleteNote("pattern-coder"));
        assertTrue(vault.readNoteBody("pattern-coder").isEmpty());
    }

    @Test
    void loginReopensPersistedVault() {
        KeysteadVault vault = newVault();
        vault.signup("alice", "p@ssw0rd!");
        vault.saveNote("pattern-coder", "sk-xxx");
        vault.logout("alice");

        // A fresh KeysteadVault instance (simulating a restart) reopens the same persisted vault.
        KeysteadVault reopened = newVault();
        reopened.login("alice", "p@ssw0rd!");
        assertEquals(Optional.of("sk-xxx"), reopened.readNoteBody("pattern-coder"));
    }

    @Test
    void saveNoteUpsertDoesNotDuplicate() {
        KeysteadVault vault = newVault();
        vault.signup("alice", "p@ssw0rd!");
        vault.saveNote("pattern-coder", "sk-old");
        vault.saveNote("pattern-coder", "sk-new");

        assertEquals(Optional.of("sk-new"), vault.readNoteBody("pattern-coder"));
        Set<String> titles = vault.listTitles();
        assertEquals(
                1, titles.stream().filter("pattern-coder"::equals).count(), "no duplicate titles");
    }

    @Test
    void lockedVaultRejectsOperations() {
        KeysteadVault vault = newVault();
        assertThrows(
                KeysteadVault.VaultLockedException.class, () -> vault.readNoteBody("anything"));
        assertThrows(KeysteadVault.VaultLockedException.class, () -> vault.saveNote("k", "v"));
    }

    @Test
    void wrongPasswordFailsToOpen() {
        KeysteadVault vault = newVault();
        vault.signup("alice", "p@ssw0rd!");
        vault.logout("alice");

        KeysteadVault reopened = newVault();
        assertThrows(Exception.class, () -> reopened.login("alice", "wrong-password"));
    }
}
