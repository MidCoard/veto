package top.focess.veto.vault;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import top.focess.veto.command.PromptHandler;

/**
 * Unified service for managing user authentication and vault lifecycle. Ensures that login and
 * logout operations are performed consistently across all frontends (REST API/UI and terminal CLI).
 *
 * <p>The vault is keystead-backed: {@code login} opens the user's vault with their password, {@code
 * signup} creates and opens it. keystead performs the KDF and vault-key wrapping internally, so
 * this layer no longer handles master/vault key derivation.
 */
@Service
public class AuthLifecycleManager {

    private static final Logger log = LoggerFactory.getLogger(AuthLifecycleManager.class);

    private final KeysteadVault vault;
    private final PromptHandler promptHandler;

    public AuthLifecycleManager(
            @NonNull KeysteadVault vault, @NonNull PromptHandler promptHandler) {
        this.vault = vault;
        this.promptHandler = promptHandler;
    }

    /**
     * Performs a unified signup: creates the user's keystead vault and opens it.
     *
     * @param username the name of the user signing up
     * @param password the user's login password (also the vault master password)
     */
    public synchronized void signup(@NonNull String username, @NonNull String password) {
        log.info("AuthLifecycleManager: Signing up user '{}'", username);
        vault.signup(username, password);
    }

    /**
     * Performs a unified login: opens the user's keystead vault.
     *
     * @param username the name of the user logging in
     * @param password the user's login password (also the vault master password)
     */
    public synchronized void login(@NonNull String username, @NonNull String password) {
        log.info("AuthLifecycleManager: Logging in user '{}'", username);
        vault.login(username, password);
    }

    /**
     * Performs a unified logout: detaches the user's terminals and closes their vault handle. The
     * persisted vault is untouched and can be reopened on re-login.
     *
     * @param username the name of the user logging out
     */
    public synchronized void logout(@NonNull String username) {
        log.info("AuthLifecycleManager: Logging out user '{}'", username);
        try {
            promptHandler.deactivateUser(username);
        } catch (Exception e) {
            log.warn("Error detaching sessions for user '{}' during logout", username, e);
        }
        vault.logout(username);
    }
}
