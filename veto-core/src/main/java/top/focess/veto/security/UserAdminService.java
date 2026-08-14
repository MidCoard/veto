package top.focess.veto.security;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.focess.veto.model.AgentInstanceRepository;
import top.focess.veto.model.AgentPatternRepository;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.vault.AuthLifecycleManager;
import top.focess.veto.vault.KeysteadVault;
import top.focess.veto.vault.UserEntity;
import top.focess.veto.vault.UserRegistry;

/**
 * Admin operations on user accounts: provisioning, deletion (with cascade), password reset, and
 * role/lookup helpers. Backs the {@code /user} commands.
 *
 * <p>Deletion cascades across agents (per session), sessions, patterns, the keystead vault store,
 * and finally the user row, so no orphan rows survive a removed user - this is the structural fix
 * for the stale-patterns-on-re-signup bug.
 */
@Service
public class UserAdminService {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.security.UserAdminService");

    private final @NonNull UserRegistry users;
    private final @NonNull AgentPatternRepository patterns;
    private final @NonNull SessionRepository sessions;
    private final @NonNull AgentInstanceRepository agents;
    private final @NonNull KeysteadVault vault;
    private final @NonNull AuthLifecycleManager auth;

    public UserAdminService(
            @NonNull UserRegistry users,
            @NonNull AgentPatternRepository patterns,
            @NonNull SessionRepository sessions,
            @NonNull AgentInstanceRepository agents,
            @NonNull KeysteadVault vault,
            @NonNull AuthLifecycleManager auth) {
        this.users = users;
        this.patterns = patterns;
        this.sessions = sessions;
        this.agents = agents;
        this.vault = vault;
        this.auth = auth;
    }

    /**
     * Provisions a new account: creates the user row and a <em>closed</em> keystead vault (not
     * unlocked - the user opens it on first login). The admin's own session/vault is untouched.
     */
    @Transactional
    public void create(@NonNull String username, @NonNull String password, @NonNull String role) {
        users.create(username, password, role);
        vault.createVault(username, password);
    }

    /**
     * Deletes a user and cascades: in-memory detach -> agents (per session) -> sessions -> patterns
     * -> keystead vault store -> user row. The DB-owned deletes run in one transaction; vault-file
     * cleanup is best-effort.
     */
    @Transactional
    public void deleteUser(@NonNull String username) {
        try {
            auth.logout(username);
        } catch (Exception e) {
            log.debug(
                    "UserAdminService: logout during delete of '{}' skipped: {}",
                    username,
                    java.util.Objects.toString(e.getMessage()));
        }
        for (SessionEntity s : sessions.findByOwner(username)) {
            agents.deleteBySessionId(s.getId());
        }
        sessions.deleteByOwner(username);
        patterns.deleteByOwner(username);
        users.deleteByUsername(username);
        vault.deleteVaultStore(username);
    }

    /** Count of ADMIN users (for the last-admin guard). */
    public long adminCount() {
        return users.adminCount();
    }

    /** Whether the user exists and has the ADMIN role. */
    public boolean isAdmin(@NonNull String username) {
        return users.isAdmin(username);
    }

    /** Lists every user (admin only). */
    public @NonNull List<UserEntity> listAll() {
        return users.listAll();
    }

    /** Resets the password (new Argon2id hash; invalidates the existing vault). */
    public void setPassword(@NonNull String username, @NonNull String password) {
        users.setPassword(username, password);
    }
}
