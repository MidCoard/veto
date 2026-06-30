package top.focess.veto.vault;

import javax.crypto.SecretKey;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import top.focess.veto.command.PromptHandler;

/**
 * Unified service for managing user authentication and vault lifecycle. Ensures that login and
 * logout operations are performed consistently across all frontends (REST API/UI and terminal CLI).
 */
@Service
public class AuthLifecycleManager {

    private static final Logger log = LoggerFactory.getLogger(AuthLifecycleManager.class);

    private final CredentialVault vault;
    private final PromptHandler promptHandler;

    public AuthLifecycleManager(
            @NonNull CredentialVault vault, @NonNull PromptHandler promptHandler) {
        this.vault = vault;
        this.promptHandler = promptHandler;
    }

    /**
     * Performs a unified login: unlocks the user's credential vault.
     *
     * @param username the name of the user logging in
     * @param vaultKey the decrypted vault key for the user
     */
    public synchronized void login(@NonNull String username, @NonNull SecretKey vaultKey) {
        log.info("AuthLifecycleManager: Logging in user '{}'", username);
        vault.unlock(vaultKey, username);
    }

    /**
     * Performs a unified logout: deactivates any running agents for the user, wipes transient
     * credentials, and locks the vault.
     *
     * @param username the name of the user logging out
     */
    public synchronized void logout(@NonNull String username) {
        log.info("AuthLifecycleManager: Logging out user '{}'", username);
        try {
            promptHandler.deactivateAgent(username);
        } catch (Exception e) {
            log.warn("Error deactivating agent for user '{}' during logout", username, e);
        }
        vault.lock();
    }
}
