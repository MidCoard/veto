package top.focess.veto.vault;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Optional;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecureStoreTest {

    private SecureStore secureStore;
    private CredentialVaultConfiguration config;
    private SecretKey vaultKey;

    @TempDir Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        config = new CredentialVaultConfiguration();
        config.setVaultHome(tempDir.resolve(".veto").toString());
        secureStore = new SecureStore(config, "test-user");
        secureStore.initialize();

        // Generate a test Vault Key and unlock
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(256, SecureRandom.getInstanceStrong());
        vaultKey = kg.generateKey();
        secureStore.unlock(vaultKey);
    }

    @Test
    void testStoreAndRetrieve() {
        secureStore.store("api-key-openai", "sk-test123");
        secureStore.store("ssh-profile", "user@host:22");

        Optional<String> apiKey = secureStore.retrieve("api-key-openai");
        assertTrue(apiKey.isPresent());
        assertEquals("sk-test123", apiKey.get());

        Optional<String> sshProfile = secureStore.retrieve("ssh-profile");
        assertTrue(sshProfile.isPresent());
        assertEquals("user@host:22", sshProfile.get());
    }

    @Test
    void testRetrieveNonexistent() {
        Optional<String> result = secureStore.retrieve("nonexistent-key");
        assertFalse(result.isPresent());
    }

    @Test
    void testListKeys() {
        secureStore.store("key1", "val1");
        secureStore.store("key2", "val2");

        var keys = secureStore.listKeys();
        assertTrue(keys.contains("key1"));
        assertTrue(keys.contains("key2"));
        assertEquals(2, keys.size());
    }

    @Test
    void testDelete() {
        secureStore.store("temp-key", "temp-value");
        assertTrue(secureStore.exists("temp-key"));

        secureStore.delete("temp-key");
        assertFalse(secureStore.exists("temp-key"));
    }

    @Test
    void testPersistenceAcrossInstances() {
        secureStore.store("persistent-key", "persistent-value");

        // Create a second store with the same config and unlock with the same key
        SecureStore store2 = new SecureStore(config, "test-user");
        store2.initialize();
        store2.unlock(vaultKey);

        Optional<String> retrieved = store2.retrieve("persistent-key");
        assertTrue(retrieved.isPresent());
        assertEquals("persistent-value", retrieved.get());
    }

    @Test
    void testLockWipesCredentials() {
        secureStore.store("key", "value");
        assertTrue(secureStore.retrieve("key").isPresent());

        secureStore.lock();

        // After lock, operations should throw
        assertThrows(SecureStore.VaultLockedException.class, () -> secureStore.retrieve("key"));
    }
}
