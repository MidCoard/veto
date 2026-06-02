package top.focess.veto.vault;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * In-memory session manager. Maps opaque UUID tokens to active sessions. The Vault Key is managed
 * by {@link CredentialVault} — this class only tracks which user is authenticated.
 */
@Component
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    public String createSession(String username) {
        String token = UUID.randomUUID().toString();
        sessions.put(token, new Session(token, username, Instant.now()));
        log.info("Session created for user '{}'", username);
        return token;
    }

    public Optional<Session> validate(String token) {
        if (token == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(sessions.get(token));
    }

    public void invalidate(String token) {
        Session removed = sessions.remove(token);
        if (removed != null) {
            log.info("Session invalidated for user '{}'", removed.username);
        }
    }

    public int activeSessionCount() {
        return sessions.size();
    }

    public record Session(String token, String username, Instant createdAt) {
    }
}
