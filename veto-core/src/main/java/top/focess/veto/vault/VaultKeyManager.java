package top.focess.veto.vault;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Manages the Vault Key — the AES-256 key that encrypts all API keys in {@code credentials.enc}.
 *
 * <p>The Vault Key itself is never stored in plaintext. It is wrapped (encrypted) with each user's
 * Master Key, which is derived from their login password via Argon2id. This way:
 *
 * <ul>
 *   <li>Password changes only re-encrypt the Vault Key (32 bytes), not every API key.
 *   <li>Multiple users each have their own encrypted copy of the same Vault Key.
 *   <li>The Vault Key only exists in memory while a user is logged in.
 * </ul>
 */
@Component
public class VaultKeyManager {

    private static final Logger log = LoggerFactory.getLogger(VaultKeyManager.class);

    private static final String WRAP_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_IV_LENGTH = 12;
    private static final int AES_KEY_SIZE = 256;
    private static final int ARGON2_MEMORY_KB = 64 * 1024; // 64 MB
    private static final int ARGON2_ITERATIONS = 3;
    private static final int ARGON2_PARALLELISM = 4;
    private static final String KEY_FILE_HEADER = "VETO_VAULT_KEY_V1";

    private final CredentialVaultConfiguration config;
    private final Path vaultDir;

    public VaultKeyManager(CredentialVaultConfiguration config) {
        this.config = config;
        this.vaultDir = Path.of(config.getVaultHome(), "vault");
    }

    // ── Key derivation (Argon2id) ──────────────────────────────────────────

    /**
     * Derives a 256-bit Master Key from username + password, salted with the user's per-user salt.
     * Both the salt and the username provide isolation: even with identical passwords, different
     * usernames produce different keys.
     */
    public SecretKey deriveMasterKey(String username, String password, byte[] userSalt) {
        String material = username + ":" + password;
        Argon2Parameters params =
                new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                        .withSalt(userSalt)
                        .withParallelism(ARGON2_PARALLELISM)
                        .withMemoryAsKB(ARGON2_MEMORY_KB)
                        .withIterations(ARGON2_ITERATIONS)
                        .build();
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(params);
        byte[] keyBytes = new byte[AES_KEY_SIZE / 8];
        generator.generateBytes(material.toCharArray(), keyBytes);
        return new SecretKeySpec(keyBytes, "AES");
    }

    // ── Vault Key lifecycle ─────────────────────────────────────────────────

    /** Generates a new random AES-256 Vault Key. Called once during first-run setup. */
    public SecretKey generateVaultKey() {
        try {
            KeyGenerator kg = KeyGenerator.getInstance("AES");
            kg.init(AES_KEY_SIZE, SecureRandom.getInstanceStrong());
            return kg.generateKey();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("AES not available", e);
        }
    }

    /**
     * Wraps (encrypts) the Vault Key with a user's Master Key and persists it to {@code
     * vault-key.enc}. If the file already contains entries for other users, the new entry is added
     * without disturbing existing ones.
     */
    public synchronized void wrapVaultKey(
            SecretKey vaultKey, SecretKey masterKey, String username) {
        try {
            Files.createDirectories(vaultDir);

            // Read existing entries
            Map<String, String> entries = loadKeyFile();

            // Wrap Vault Key for this user
            String wrapped = encryptKey(vaultKey, masterKey);
            entries.put(username, wrapped);

            saveKeyFile(entries);
            log.info("Vault Key wrapped for user '{}'", username);
        } catch (Exception e) {
            throw new RuntimeException("Failed to wrap vault key", e);
        }
    }

    /**
     * Unwraps (decrypts) the Vault Key using a user's Master Key. Returns {@code null} if the user
     * has no entry in the key file (should not happen after setup, but handled gracefully).
     */
    public synchronized SecretKey unwrapVaultKey(SecretKey masterKey, String username) {
        Map<String, String> entries = loadKeyFile();
        String wrapped = entries.get(username);
        if (wrapped == null) {
            log.warn("No wrapped Vault Key found for user '{}'", username);
            return null;
        }
        try {
            return decryptKey(wrapped, masterKey);
        } catch (Exception e) {
            log.error("Failed to unwrap Vault Key for user '{}'", username, e);
            return null;
        }
    }

    /** Returns true if the vault has been set up (vault-key.enc exists with at least one entry). */
    public boolean isSetupComplete() {
        Map<String, String> entries = loadKeyFile();
        return !entries.isEmpty();
    }

    // ── AES-GCM key wrapping ────────────────────────────────────────────────

    private String encryptKey(SecretKey plainKey, SecretKey wrappingKey) throws Exception {
        byte[] iv = new byte[GCM_IV_LENGTH];
        SecureRandom.getInstanceStrong().nextBytes(iv);
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);

        Cipher cipher = Cipher.getInstance(WRAP_ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey, spec);
        byte[] ciphertext = cipher.doFinal(plainKey.getEncoded());

        // Prepend IV to ciphertext
        byte[] combined = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);

        return Base64.getEncoder().encodeToString(combined);
    }

    private SecretKey decryptKey(String wrapped, SecretKey wrappingKey) throws Exception {
        byte[] combined = Base64.getDecoder().decode(wrapped);
        if (combined.length < GCM_IV_LENGTH) {
            throw new IllegalArgumentException("Invalid wrapped key blob");
        }

        byte[] iv = new byte[GCM_IV_LENGTH];
        byte[] ciphertext = new byte[combined.length - GCM_IV_LENGTH];
        System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
        System.arraycopy(combined, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);

        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        Cipher cipher = Cipher.getInstance(WRAP_ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey, spec);
        byte[] plaintext = cipher.doFinal(ciphertext);

        return new SecretKeySpec(plaintext, "AES");
    }

    // ── Key file persistence ────────────────────────────────────────────────

    private Path keyFilePath() {
        return vaultDir.resolve("vault-key.enc");
    }

    private Map<String, String> loadKeyFile() {
        Map<String, String> entries = new LinkedHashMap<>();
        Path path = keyFilePath();
        if (!Files.exists(path)) {
            return entries;
        }
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String header = reader.readLine();
            if (!KEY_FILE_HEADER.equals(header)) {
                log.warn("Invalid vault-key file header");
                return entries;
            }
            String line;
            while ((line = reader.readLine()) != null) {
                int sep = line.indexOf(':');
                if (sep > 0) {
                    entries.put(line.substring(0, sep), line.substring(sep + 1));
                }
            }
        } catch (IOException e) {
            log.error("Failed to load vault-key file", e);
        }
        return entries;
    }

    private void saveKeyFile(Map<String, String> entries) throws IOException {
        Path path = keyFilePath();
        try (BufferedWriter writer = Files.newBufferedWriter(path)) {
            writer.write(KEY_FILE_HEADER);
            writer.newLine();
            for (var entry : entries.entrySet()) {
                writer.write(entry.getKey() + ":" + entry.getValue());
                writer.newLine();
            }
            writer.flush();
        }
    }
}
