package top.focess.veto.vault;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Optional;
import java.util.Set;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * C8 Local Credential Vault — top-level service. Each user has their own credential file at {@code
 * credentials/{username}.enc}, encrypted with a per-user Vault Key. No user can read another user's
 * credentials.
 *
 * <p>The vault starts LOCKED. A user must authenticate to unwrap their Vault Key and call {@link
 * #unlock(SecretKey, String)}. Only one user is active at a time per vault instance.
 */
@Service
public class CredentialVault {

    private static final Logger log = LoggerFactory.getLogger(CredentialVault.class);

    private final CredentialVaultConfiguration config;
    private final InjectionService injectionService;

    private volatile boolean unlocked = false;
    private volatile String currentUser;
    private volatile SecureStore currentStore;

    public CredentialVault(CredentialVaultConfiguration config, InjectionService injectionService) {
        this.config = config;
        this.injectionService = injectionService;
    }

    /**
     * Initializes the vault directory. Vault remains LOCKED until a user logs in.
     */
    @PostConstruct
    public void init() {
        log.info("C8 CredentialVault: Initialized (LOCKED). Waiting for authentication.");
    }

    /** Logs shutdown state. */
    @PreDestroy
    public void shutdown() {
        log.info(
                "C8 CredentialVault: Shut down with {} active injection sessions",
                injectionService.getActiveInjectionCount());
    }

    // ── Lock / Unlock ───────────────────────────────────────────────────────

    /**
     * Unlocks the vault for a specific user with their Vault Key.
     */
    public synchronized void unlock(SecretKey vaultKey, String username) {
        currentStore = new SecureStore(config, username);
        currentStore.initialize();
        currentStore.unlock(vaultKey);
        currentUser = username;
        unlocked = true;
        log.info(
                "C8 CredentialVault: Unlocked for user '{}'. {} credentials available.",
                username,
                currentStore.listKeys().size());
    }

    /**
     * Locks the vault and wipes the current user's decrypted credentials from memory.
     */
    public synchronized void lock() {
        if (currentStore != null) {
            currentStore.lock();
        }
        currentStore = null;
        currentUser = null;
        unlocked = false;
        log.info("C8 CredentialVault: Locked.");
    }

    public boolean isUnlocked() {
        return unlocked;
    }

    public String getCurrentUser() {
        return currentUser;
    }

    // ── Credential operations ───────────────────────────────────────────────

    public void store(String key, String value) {
        requireStore().store(key, value);
    }

    public Optional<String> retrieve(String key) {
        return requireStore().retrieve(key);
    }

    public void delete(String key) {
        requireStore().delete(key);
    }

    public Set<String> listKeys() {
        return requireStore().listKeys();
    }

    public InjectionService getInjectionService() {
        return injectionService;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private SecureStore requireStore() {
        if (currentStore == null || !unlocked) {
            throw new SecureStore.VaultLockedException("Vault is locked — authenticate first");
        }
        return currentStore;
    }
}
