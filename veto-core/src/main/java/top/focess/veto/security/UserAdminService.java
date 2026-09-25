package top.focess.veto.security;

import static top.focess.veto.util.LogValues.safe;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.focess.veto.agent.continuation.RequestContinuationStore;
import top.focess.veto.agent.intercept.HitlRecordRepository;
import top.focess.veto.integration.plugins.PluginLifecycleEvents;
import top.focess.veto.integration.plugins.storage.ScopedPluginStorage;
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
    private PluginLifecycleEvents pluginEvents;

    /** Setter-injects the plugin lifecycle event sink notified on user/session deletion. */
    @Autowired
    public void attachPluginEvents(@NonNull PluginLifecycleEvents events) {
        pluginEvents = events;
    }

    private ScopedPluginStorage pluginStorage;

    /** Setter-injects the scoped plugin storage purged on user deletion. */
    @Autowired
    public void attachPluginStorage(@NonNull ScopedPluginStorage storage) {
        pluginStorage = storage;
    }

    private HitlRecordRepository hitlRecords;

    /** Setter-injects the HITL record repository purged on user deletion. */
    @Autowired
    public void attachHitlRecords(@NonNull HitlRecordRepository records) {
        hitlRecords = records;
    }

    private RequestContinuationStore continuations;

    /** Setter-injects the request-continuation store purged on user deletion. */
    @Autowired
    public void attachContinuations(@NonNull RequestContinuationStore store) {
        continuations = store;
    }

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.security.UserAdminService");

    private final @NonNull UserRegistry users;
    private final @NonNull AgentPatternRepository patterns;
    private final @NonNull SessionRepository sessions;
    private final @NonNull AgentInstanceRepository agents;
    private final @NonNull KeysteadVault vault;
    private final @NonNull AuthLifecycleManager auth;

    /** Creates the service over the user, pattern, session, agent, vault, and auth stores. */
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
        var dataEvents = pluginEvents;
        if (dataEvents != null) dataEvents.beforeOwnerDeleted(username);
        try {
            auth.logout(username);
        } catch (Exception e) {
            log.debug(
                    "UserAdminService: logout during delete of '{}' skipped: {}",
                    username,
                    safe(e.getMessage()));
        }
        for (SessionEntity s : sessions.findByOwner(username)) {
            Runnable notifyDeleted =
                    () -> {
                        var events = pluginEvents;
                        if (events != null) events.sessionClosed(username, s.getId());
                    };
            if (TransactionSynchronizationManager.isSynchronizationActive())
                TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                notifyDeleted.run();
                            }
                        });
            else notifyDeleted.run();
            RequestContinuationStore store = continuations;
            if (store != null) store.deleteSession(s.getId());
            if (hitlRecords != null) hitlRecords.deleteBySessionId(s.getId());
            agents.deleteBySessionId(s.getId());
        }
        if (pluginStorage != null) pluginStorage.deleteUser(username);
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
