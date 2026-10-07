package top.focess.veto.vault;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Tracks authenticated client logins using opaque tokens. These login sessions are distinct from
 * agent conversation sessions. The Vault Key is managed by {@link KeysteadVault}.
 */
@Component
public class LoginSessionManager {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.vault.LoginSessionManager");

    private final @NonNull ConcurrentHashMap<String, LoginSession> loginSessions =
            new ConcurrentHashMap<>();

    /** Creates a client login session for the user, returning its opaque token. */
    public @NonNull String createLoginSession(@NonNull UUID userId, @NonNull String username) {
        String token = UUID.randomUUID().toString();
        loginSessions.put(token, new LoginSession(token, userId, username, Instant.now()));
        log.info("Login session created for user '{}'", username);
        return token;
    }

    /** Returns the login session bound to the token, or empty when the token is unknown. */
    public @NonNull Optional<LoginSession> validateToken(@NonNull String token) {
        return Optional.ofNullable(loginSessions.get(token));
    }

    /** Revokes a login token; no-op when the token is unknown. */
    public void revokeToken(@NonNull String token) {
        LoginSession removed = loginSessions.remove(token);
        if (removed != null) {
            log.info("Login session revoked for user '{}'", removed.username);
        }
    }

    /** Number of authenticated client logins, independent of agent conversations. */
    public int activeLoginSessionCount() {
        return loginSessions.size();
    }

    /** Revokes every token belonging to an account before its vault is closed or deleted. */
    public void revokeUserTokens(@NonNull UUID userId) {
        loginSessions.values().removeIf(loginSession -> userId.equals(loginSession.userId()));
    }

    /** Whether this account still has an authenticated client. */
    public boolean hasLoginSessions(@NonNull UUID userId) {
        return loginSessions.values().stream()
                .anyMatch(loginSession -> userId.equals(loginSession.userId()));
    }

    /** An authenticated client login bound to an opaque token, not an agent conversation. */
    public record LoginSession(
            @NonNull String token,
            @NonNull UUID userId,
            @NonNull String username,
            @NonNull Instant createdAt) {}
}
