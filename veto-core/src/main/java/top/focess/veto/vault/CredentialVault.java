package top.focess.veto.vault;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
 * #unlock(SecretKey, String)}. Supports multiple concurrent active users.
 */
@Service
public class CredentialVault {

    private static final Logger log = LoggerFactory.getLogger(CredentialVault.class);

    private final CredentialVaultConfiguration config;
    private final InjectionService injectionService;

    private final ConcurrentHashMap<String, SecureStore> activeStores = new ConcurrentHashMap<>();

    public CredentialVault(CredentialVaultConfiguration config, InjectionService injectionService) {
        this.config = config;
        this.injectionService = injectionService;
    }

    /** Initializes the vault directory. Vault remains LOCKED until a user logs in. */
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

    /** Unlocks the vault for a specific user with their Vault Key. */
    public synchronized void unlock(SecretKey vaultKey, String username) {
        SecureStore store = new SecureStore(config, username);
        store.initialize();
        store.unlock(vaultKey);
        activeStores.put(username, store);
        log.info(
                "C8 CredentialVault: Unlocked for user '{}'. {} credentials available.",
                username,
                store.listKeys().size());
    }

    /** Locks the vault for the user in the current context, or all users if no context exists. */
    public synchronized void lock() {
        String user = UserContext.get();
        if (user != null) {
            lock(user);
        } else {
            activeStores.values().forEach(SecureStore::lock);
            activeStores.clear();
            log.info("C8 CredentialVault: Locked all stores.");
        }
    }

    /** Locks the vault for a specific user. */
    public synchronized void lock(String username) {
        SecureStore store = activeStores.remove(username);
        if (store != null) {
            store.lock();
        }
        log.info("C8 CredentialVault: Locked for user '{}'.", username);
    }

    public boolean isUnlocked() {
        String user = UserContext.get();
        if (user != null) {
            return isUnlocked(user);
        }
        return !activeStores.isEmpty();
    }

    public boolean isUnlocked(String username) {
        SecureStore store = activeStores.get(username);
        return store != null && store.isUnlocked();
    }

    public String getCurrentUser() {
        String user = UserContext.get();
        if (user != null && activeStores.containsKey(user)) {
            return user;
        }
        if (activeStores.size() == 1) {
            return activeStores.keySet().iterator().next();
        }
        return null;
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
        String user = UserContext.get();
        if (user == null) {
            if (activeStores.size() == 1) {
                return activeStores.values().iterator().next();
            }
            throw new SecureStore.VaultLockedException("Vault is locked — authenticate first");
        }
        SecureStore store = activeStores.get(user);
        if (store == null || !store.isUnlocked()) {
            throw new SecureStore.VaultLockedException("Vault is locked for user: " + user);
        }
        return store;
    }
}
