package top.focess.veto.vault;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.KeySpec;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * C8 Secure Store - encrypted on-disk storage for credentials. Uses AES-256-GCM with a key derived
 * from a master password via PBKDF2. The store file is a map of credential names to encrypted
 * blobs.
 */
@Component
public class SecureStore {

    private static final Logger log = LoggerFactory.getLogger(SecureStore.class);

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_IV_LENGTH = 12;
    private static final int AES_KEY_SIZE = 256;
    private static final int SALT_LENGTH = 32;
    private static final String KEY_DERIVATION_ALGO = "PBKDF2WithHmacSHA256";
    private static final String STORE_HEADER = "VETO_CREDENTIAL_STORE_V1";

    private final CredentialVaultConfiguration config;
    private final Path storePath;
    private volatile boolean initialized = false;
    private SecretKey storeKey;

    // In-memory credential cache (encrypted blobs)
    private final Map<String, String> credentialCache = new LinkedHashMap<>();

    /**
     * Constructs a new SecureStore with the specified configuration.
     *
     * @param config the configuration for the secure store
     */
    public SecureStore(CredentialVaultConfiguration config) {
        this.config = config;
        this.storePath = Path.of(config.getStorePath());
    }

    /**
     * Initialize the secure store. Creates the store file if it doesn't exist. Derives the
     * encryption key from the master environment variable.
     */
    public synchronized void initialize() {
        if (initialized) return;

        String masterKey = System.getenv(config.getMasterKeyEnv());
        if (masterKey == null || masterKey.isBlank()) {
            // Also check system property (useful for testing)
            masterKey = System.getProperty(config.getMasterKeyEnv());
        }
        if (masterKey == null || masterKey.isBlank()) {
            log.warn(
                    "C8 Vault: Master key not found in env '{}'. "
                            + "Vault will use a generated ephemeral key (lost on restart). "
                            + "Set {} environment variable for persistence.",
                    config.getMasterKeyEnv(),
                    config.getMasterKeyEnv());
            // Fallback: generate ephemeral key for development
            try {
                KeyGenerator kg = KeyGenerator.getInstance("AES");
                kg.init(AES_KEY_SIZE);
                storeKey = kg.generateKey();
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException("AES not available", e);
            }
        } else {
            try {
                storeKey = deriveKey(masterKey);
            } catch (Exception e) {
                throw new RuntimeException("Failed to derive vault key", e);
            }
        }

        // Create parent directories
        try {
            Files.createDirectories(storePath.getParent());
        } catch (IOException e) {
            log.warn("C8 Vault: Cannot create store directory", e);
        }

        // Load existing store
        loadStore();

        initialized = true;
        log.info(
                "C8 Vault: Secure store initialized at '{}' ({} credentials loaded)",
                storePath,
                credentialCache.size());
    }

    /**
     * Store a credential securely (encrypted at rest).
     */
    public synchronized void store(String key, String value) {
        if (!initialized) initialize();

        try {
            String encrypted = encrypt(value);
            credentialCache.put(key, encrypted);
            saveStore();
            log.debug("C8 Vault: Stored credential '{}'", key);
        } catch (Exception e) {
            log.error("C8 Vault: Failed to store credential '{}'", key, e);
        }
    }

    /** Retrieve a decrypted credential. */
    public synchronized Optional<String> retrieve(String key) {
        if (!initialized) initialize();

        String encrypted = credentialCache.get(key);
        if (encrypted == null) {
            log.debug("C8 Vault: Credential '{}' not found", key);
            return Optional.empty();
        }

        try {
            String decrypted = decrypt(encrypted);
            return Optional.of(decrypted);
        } catch (Exception e) {
            log.error("C8 Vault: Failed to decrypt credential '{}'", key, e);
            return Optional.empty();
        }
    }

    /** Delete a stored credential. */
    public synchronized void delete(String key) {
        if (!initialized) initialize();

        credentialCache.remove(key);
        saveStore();
        log.debug("C8 Vault: Deleted credential '{}'", key);
    }

    /**
     * List all stored credential keys (not the values).
     *
     * @return a set of all credential keys
     */
    public Set<String> listKeys() {
        if (!initialized) initialize();
        return Collections.unmodifiableSet(credentialCache.keySet());
    }

    /**
     * Check if a credential exists in the secure store.
     *
     * @param key the key to check
     * @return true if it exists, false otherwise
     */
    public boolean exists(String key) {
        if (!initialized) initialize();
        return credentialCache.containsKey(key);
    }

    // ---- Encryption / Decryption ----

    private String encrypt(String plaintext) throws Exception {
        byte[] iv = new byte[GCM_IV_LENGTH];
        SecureRandom.getInstanceStrong().nextBytes(iv);
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, storeKey, spec);
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        // Prepend IV to ciphertext
        byte[] combined = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);

        return Base64.getEncoder().encodeToString(combined);
    }

    private String decrypt(String encrypted) throws Exception {
        byte[] combined = Base64.getDecoder().decode(encrypted);
        if (combined.length < GCM_IV_LENGTH) {
            throw new IllegalArgumentException("Invalid encrypted blob");
        }

        byte[] iv = new byte[GCM_IV_LENGTH];
        byte[] ciphertext = new byte[combined.length - GCM_IV_LENGTH];
        System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
        System.arraycopy(combined, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);

        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, storeKey, spec);
        byte[] plaintext = cipher.doFinal(ciphertext);

        return new String(plaintext, StandardCharsets.UTF_8);
    }

    private SecretKey deriveKey(String masterPassword) throws Exception {
        byte[] salt = loadSalt();
        if (salt == null) {
            salt = new byte[SALT_LENGTH];
            SecureRandom.getInstanceStrong().nextBytes(salt);
            saveSalt(salt);
        }

        KeySpec spec =
                new PBEKeySpec(
                        masterPassword.toCharArray(), salt,
                        config.getKeyDerivationIterations(), AES_KEY_SIZE);
        SecretKeyFactory factory = SecretKeyFactory.getInstance(KEY_DERIVATION_ALGO);
        return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
    }

    // ---- Persistence ----

    private void loadStore() {
        if (!Files.exists(storePath)) {
            log.info("C8 Vault: No existing store file at '{}', creating new", storePath);
            return;
        }

        try (BufferedReader reader = Files.newBufferedReader(storePath)) {
            String header = reader.readLine();
            if (!STORE_HEADER.equals(header)) {
                log.warn("C8 Vault: Invalid store header");
                return;
            }

            String line;
            while ((line = reader.readLine()) != null) {
                int sep = line.indexOf(':');
                if (sep > 0) {
                    String key = line.substring(0, sep);
                    String value = line.substring(sep + 1);
                    credentialCache.put(key, value);
                }
            }
        } catch (IOException e) {
            log.error("C8 Vault: Failed to load store", e);
        }
    }

    private synchronized void saveStore() {
        try (BufferedWriter writer = Files.newBufferedWriter(storePath)) {
            writer.write(STORE_HEADER);
            writer.newLine();
            for (var entry : credentialCache.entrySet()) {
                writer.write(entry.getKey() + ":" + entry.getValue());
                writer.newLine();
            }
            writer.flush();
        } catch (IOException e) {
            log.error("C8 Vault: Failed to save store", e);
        }
    }

    // ---- Salt management ----

    private byte[] loadSalt() {
        Path saltPath = storePath.resolveSibling(storePath.getFileName() + ".salt");
        try {
            if (Files.exists(saltPath)) {
                return Files.readAllBytes(saltPath);
            }
        } catch (IOException e) {
            log.warn("C8 Vault: Cannot load salt", e);
        }
        return null;
    }

    private void saveSalt(byte[] salt) {
        Path saltPath = storePath.resolveSibling(storePath.getFileName() + ".salt");
        try {
            Files.write(saltPath, salt);
        } catch (IOException e) {
            log.warn("C8 Vault: Cannot save salt", e);
        }
    }

    /**
     * Returns whether the secure store has been initialized.
     *
     * @return true if initialized, false otherwise
     */
    public boolean isInitialized() {
        return initialized;
    }
}
