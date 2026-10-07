package top.focess.veto.vault;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import top.focess.veto.api.event.UserLoggedInEvent;
import top.focess.veto.api.event.UserLogoutEvent;
import top.focess.veto.api.event.UserRegisteredEvent;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.command.PromptHandler;
import top.focess.veto.event.EventManager;
import top.focess.veto.terminal.IpcServer;

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
    private final @NonNull EventManager eventManager;

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.vault.AuthLifecycleManager");

    private final @NonNull KeysteadVault vault;
    private final @NonNull PromptHandler promptHandler;
    private final @NonNull LoginSessionManager loginSessions;
    private final @NonNull ObjectProvider<IpcServer> ipcServers;

    /** Constructs the manager with vault access, terminal detachment, and event delivery. */
    public AuthLifecycleManager(
            @NonNull KeysteadVault vault,
            @NonNull PromptHandler promptHandler,
            @NonNull EventManager eventManager,
            @NonNull LoginSessionManager loginSessions,
            @NonNull ObjectProvider<IpcServer> ipcServers) {
        this.vault = vault;
        this.promptHandler = promptHandler;
        this.eventManager = eventManager;
        this.loginSessions = loginSessions;
        this.ipcServers = ipcServers;
    }

    /**
     * Performs a unified signup: creates the user's keystead vault and opens it.
     *
     * @param username the name of the user signing up
     * @param password the user's login password (also the vault master password)
     */
    public synchronized void signup(@NonNull String username, @NonNull String password) {
        log.info("AuthLifecycleManager: Signing up user '{}'", username);
        UUID userId = vault.signup(username, password);
        eventManager.submit(new UserRegisteredEvent(new Scope.UserScope(userId)));
    }

    /**
     * Performs a unified login: opens the user's keystead vault.
     *
     * @param username the name of the user logging in
     * @param password the user's login password (also the vault master password)
     */
    public synchronized void login(@NonNull String username, @NonNull String password) {
        log.info("AuthLifecycleManager: Logging in user '{}'", username);
        UUID userId = vault.login(username, password);
        eventManager.submit(new UserLoggedInEvent(new Scope.UserScope(userId)));
    }

    /**
     * Performs a unified logout: detaches the user's terminals and closes their vault handle. The
     * persisted vault is untouched and can be reopened on re-login.
     *
     * @param userId the canonical identity of the user logging out
     */
    public synchronized void logout(@NonNull UUID userId) {
        log.info("AuthLifecycleManager: Logging out user {}", userId);
        loginSessions.revokeUserTokens(userId);
        var ipcServer = ipcServers.getIfAvailable();
        if (ipcServer != null) ipcServer.revokeUser(userId);
        eventManager.submit(new UserLogoutEvent(new Scope.UserScope(userId)));
        try {
            promptHandler.deactivateUser(userId);
        } catch (Exception e) {
            log.warn("Error detaching terminal agents for user '{}' during logout", userId, e);
        }
        vault.logout(userId);
    }
}
