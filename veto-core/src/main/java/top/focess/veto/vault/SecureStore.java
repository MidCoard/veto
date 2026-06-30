package top.focess.veto.vault;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * vault Secure Store — encrypted on-disk storage for a single user's credentials. Uses AES-256-GCM.
 * The encryption key (Vault Key) is provided externally via {@link #unlock(SecretKey)} after the
 * user authenticates.
 *
 * <p>Each user has their own credential file at {@code
 * {vaultHome}/vault/credentials/{username}.enc}, encrypted with a per-user Vault Key. No user can
 * read another user's credentials.
 *
 * <p>The vault starts LOCKED. No credentials can be stored or retrieved until the owning user logs
 * in.
 */
public class SecureStore {

    private static final Logger log = LoggerFactory.getLogger(SecureStore.class);

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_IV_LENGTH = 12;
    private static final String STORE_HEADER = "VETO_CREDENTIAL_STORE_V1";

    private final @NonNull Path storePath;
    private final @NonNull String username;
    private volatile boolean initialized = false;
    private volatile SecretKey storeKey;

    // In-memory credential cache (encrypted blobs)
    private final Map<String, String> credentialCache = new LinkedHashMap<>();

    public
    @NonNull
    SecureStore(@NonNull CredentialVaultConfiguration config, @NonNull String username) {
        this.username = username;
        this.storePath = Path.of(config.getVaultHome(), "vault", "credentials", username + ".enc");
    }

    /** Returns the username this store belongs to. */
    public String getUsername() {
        return username;
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    /** Initializes the store — creates directories, reads the header. Vault remains LOCKED. */
    public synchronized void initialize() {
        if (initialized) {
            return;
        }
        try {
            Files.createDirectories(storePath.getParent());
        } catch (IOException e) {
            log.warn("Vault: Cannot create store directory", e);
        }
        initialized = true;
        log.info("Vault: Secure store at '{}' (LOCKED — waiting for login)", storePath);
    }

    /**
     * Unlocks the vault with the given Vault Key. Loads and decrypts all credentials into the
     * in-memory cache.
     */
    public synchronized void unlock(@NonNull SecretKey vaultKey) {
        if (!initialized) {
            initialize();
        }
        this.storeKey = vaultKey;
        loadStore();
        log.info("Vault: Unlocked — {} credentials loaded", credentialCache.size());
    }

    /** Locks the vault, wiping the encryption key and decrypted credentials from memory. */
    public synchronized void lock() {
        this.storeKey = null;
        this.credentialCache.clear();
        log.info("Vault: Locked — credentials cleared from memory");
    }

    /** Returns true if the vault is unlocked and ready for operations. */
    public boolean isUnlocked() {
        return storeKey != null;
    }

    // ── Credential operations ───────────────────────────────────────────────

    /** Store a credential securely (encrypted at rest). */
    public synchronized void store(@NonNull String key, @NonNull String value) {
        requireUnlocked();
        try {
            String encrypted = encrypt(value);
            credentialCache.put(key, encrypted);
            saveStore();
            log.debug("Vault: Stored credential '{}'", key);
        } catch (Exception e) {
            log.error("Vault: Failed to store credential '{}'", key, e);
        }
    }

    /** Retrieve a decrypted credential. */
    public synchronized @NonNull Optional<String> retrieve(@NonNull String key) {
        requireUnlocked();
        String encrypted = credentialCache.get(key);
        if (encrypted == null) {
            log.debug("Vault: Credential '{}' not found", key);
            return Optional.empty();
        }
        try {
            return Optional.of(decrypt(encrypted));
        } catch (Exception e) {
            log.error("Vault: Failed to decrypt credential '{}'", key, e);
            return Optional.empty();
        }
    }

    /** Delete a stored credential. */
    public synchronized void delete(@NonNull String key) {
        requireUnlocked();
        credentialCache.remove(key);
        saveStore();
        log.debug("Vault: Deleted credential '{}'", key);
    }

    /** List all stored credential keys (not the values). */
    public synchronized Set<String> listKeys() {
        requireUnlocked();
        return Collections.unmodifiableSet(credentialCache.keySet());
    }

    /** Check if a credential exists in the secure store. */
    public synchronized boolean exists(@NonNull String key) {
        requireUnlocked();
        return credentialCache.containsKey(key);
    }

    /** Returns whether the secure store has been initialized. */
    public boolean isInitialized() {
        return initialized;
    }

    // ── Encryption / Decryption ─────────────────────────────────────────────

    private String encrypt(String plaintext) throws Exception {
        byte[] iv = new byte[GCM_IV_LENGTH];
        SecureRandom.getInstanceStrong().nextBytes(iv);
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, storeKey, spec);
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

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

    // ── Persistence ─────────────────────────────────────────────────────────

    private void loadStore() {
        if (!Files.exists(storePath)) {
            log.info("Vault: No existing store file at '{}', starting fresh", storePath);
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(storePath)) {
            String header = reader.readLine();
            if (!STORE_HEADER.equals(header)) {
                log.warn("Vault: Invalid store header");
                return;
            }
            String line;
            while ((line = reader.readLine()) != null) {
                int sep = line.indexOf(':');
                if (sep > 0) {
                    credentialCache.put(line.substring(0, sep), line.substring(sep + 1));
                }
            }
        } catch (IOException e) {
            log.error("Vault: Failed to load store", e);
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
            log.error("Vault: Failed to save store", e);
        }
    }

    private void requireUnlocked() {
        if (!initialized) {
            initialize();
        }
        if (storeKey == null) {
            throw new VaultLockedException("Vault is locked — authenticate first");
        }
    }

    /** Thrown when an operation is attempted on a locked vault. */
    public static class VaultLockedException extends RuntimeException {
        public
        @NonNull
        VaultLockedException(@NonNull String message) {
            super(message);
        }
    }
}
