package top.focess.veto.vault;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * C8 Local Credential Vault - top-level service. Physically isolates system API keys and SSH
 * profiles. Injects credentials temporarily into the C6 Sandbox during execution. Credentials never
 * traverse the C3 Communication Bus.
 */
@Service
public class CredentialVault {

    private static final Logger log = LoggerFactory.getLogger(CredentialVault.class);

    private final SecureStore secureStore;
    private final InjectionService injectionService;

    /**
     * Constructs a new CredentialVault with the specified secure store and injection service.
     *
     * @param secureStore the secure store for encrypted credential persistence
     * @param injectionService the service for injecting credentials into the sandbox
     */
    public CredentialVault(SecureStore secureStore, InjectionService injectionService) {
        this.secureStore = secureStore;
        this.injectionService = injectionService;
    }

    /**
     * Initializes the secure store and logs the number of available credential slots. Called by
     * Spring after dependency injection.
     */
    @PostConstruct
    public void init() {
        secureStore.initialize();
        log.info(
                "C8 CredentialVault: Initialized. {} credential slots available.",
                secureStore.listKeys().size());
    }

    /**
     * Logs the shutdown state and the number of active injection sessions.
     */
    @PreDestroy
    public void shutdown() {
        log.info(
                "C8 CredentialVault: Shut down with {} active injection sessions",
                injectionService.getActiveInjectionCount());
    }

    /**
     * Store a credential securely.
     *
     * @param key the key to identify the credential
     * @param value the secret value to store
     */
    public void store(String key, String value) {
        secureStore.store(key, value);
    }

    /**
     * Retrieve a credential value.
     *
     * @param key the key identifying the credential
     * @return an Optional containing the credential value if found, or empty otherwise
     */
    public Optional<String> retrieve(String key) {
        return secureStore.retrieve(key);
    }

    /**
     * Delete a credential.
     *
     * @param key the key identifying the credential to delete
     */
    public void delete(String key) {
        secureStore.delete(key);
    }

    /**
     * List all stored credential keys.
     *
     * @return a set of all credential keys in the vault
     */
    public Set<String> listKeys() {
        return secureStore.listKeys();
    }

    /**
     * Get the injection service for credential injection into C6 Sandbox.
     *
     * @return the injection service instance
     */
    public InjectionService getInjectionService() {
        return injectionService;
    }
}
