package top.focess.veto.vault;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecureStoreTest {

    private SecureStore secureStore;
    private CredentialVaultConfiguration config;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        System.setProperty("VETO_VAULT_KEY", "test-master-key-for-veto-vault-unit-tests");
        config = new CredentialVaultConfiguration();
        config.setStorePath(tempDir.resolve("test-creds.enc").toString());
        config.setKeyDerivationIterations(1);
        secureStore = new SecureStore(config);
    }

    @Test
    void testStoreAndRetrieve() {
        secureStore.initialize();
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
        secureStore.initialize();
        Optional<String> result = secureStore.retrieve("nonexistent-key");
        assertFalse(result.isPresent());
    }

    @Test
    void testListKeys() {
        secureStore.initialize();
        secureStore.store("key1", "val1");
        secureStore.store("key2", "val2");

        var keys = secureStore.listKeys();
        assertTrue(keys.contains("key1"));
        assertTrue(keys.contains("key2"));
        assertEquals(2, keys.size());
    }

    @Test
    void testDelete() {
        secureStore.initialize();
        secureStore.store("temp-key", "temp-value");
        assertTrue(secureStore.exists("temp-key"));

        secureStore.delete("temp-key");
        assertFalse(secureStore.exists("temp-key"));
    }

    @Test
    void testPersistenceAcrossInstances() {
        secureStore.initialize();
        secureStore.store("persistent-key", "persistent-value");

        SecureStore store2 = new SecureStore(config);
        store2.initialize();

        Optional<String> retrieved = store2.retrieve("persistent-key");
        assertTrue(retrieved.isPresent());
        assertEquals("persistent-value", retrieved.get());
    }
}
