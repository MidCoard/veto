package top.focess.veto.security;

import static top.focess.veto.util.LogValues.safe;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.focess.veto.agent.continuation.RequestContinuationStore;
import top.focess.veto.agent.intercept.HitlRecordRepository;
import top.focess.veto.api.event.SessionDeletedEvent;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.event.EventManager;
import top.focess.veto.integration.plugins.PluginDataCleanup;
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
    private final @NonNull EventManager eventManager;
    private final @NonNull PluginDataCleanup pluginDataCleanup;
    private final @NonNull ScopedPluginStorage pluginStorage;
    private final @NonNull HitlRecordRepository hitlRecords;
    private final @NonNull RequestContinuationStore continuations;

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.security.UserAdminService");

    private final @NonNull UserRegistry users;
    private final @NonNull AgentPatternRepository patterns;
    private final @NonNull SessionRepository sessions;
    private final @NonNull AgentInstanceRepository agents;
    private final @NonNull KeysteadVault vault;
    private final @NonNull AuthLifecycleManager auth;

    /** Creates the service with account stores, transactional cleanup, and event delivery. */
    public UserAdminService(
            @NonNull UserRegistry users,
            @NonNull AgentPatternRepository patterns,
            @NonNull SessionRepository sessions,
            @NonNull AgentInstanceRepository agents,
            @NonNull KeysteadVault vault,
            @NonNull AuthLifecycleManager auth,
            @NonNull EventManager eventManager,
            @NonNull PluginDataCleanup pluginDataCleanup,
            @NonNull ScopedPluginStorage pluginStorage,
            @NonNull HitlRecordRepository hitlRecords,
            @NonNull RequestContinuationStore continuations) {
        this.users = users;
        this.patterns = patterns;
        this.sessions = sessions;
        this.agents = agents;
        this.vault = vault;
        this.auth = auth;
        this.eventManager = eventManager;
        this.pluginDataCleanup = pluginDataCleanup;
        this.pluginStorage = pluginStorage;
        this.hitlRecords = hitlRecords;
        this.continuations = continuations;
    }

    /**
     * Provisions a new account: creates the user row and a <em>closed</em> keystead vault (not
     * unlocked - the user opens it on first login). The admin's own session/vault is untouched.
     */
    @Transactional
    public void create(@NonNull String username, @NonNull String password, @NonNull String role) {
        var created = users.create(username, password, role);
        vault.createVault(created.getUserId(), password);
    }

    /**
     * Deletes a user and cascades: in-memory detach -> agents (per session) -> sessions -> patterns
     * -> keystead vault store -> user row. The DB-owned deletes run in one transaction; vault-file
     * cleanup is best-effort.
     */
    @Transactional
    public void deleteUser(@NonNull UUID userId) {
        pluginDataCleanup.beforeUserDeleted(userId);
        try {
            auth.logout(userId);
        } catch (Exception e) {
            log.debug(
                    "UserAdminService: logout during delete of '{}' skipped: {}",
                    userId,
                    safe(e.getMessage()));
        }
        for (SessionEntity s : sessions.findByUserId(userId)) {
            Runnable notifyDeleted =
                    () ->
                            eventManager.submit(
                                    new SessionDeletedEvent(
                                            new Scope.SessionScope(userId, s.getId())));
            if (TransactionSynchronizationManager.isSynchronizationActive())
                TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                notifyDeleted.run();
                            }
                        });
            else notifyDeleted.run();
            continuations.deleteSession(s.getId());
            hitlRecords.deleteBySessionId(s.getId());
            agents.deleteBySessionId(s.getId());
        }
        pluginStorage.deleteUser(userId);
        sessions.deleteByUserId(userId);
        patterns.deleteByUserId(userId);
        vault.deleteVaultStore(userId);
        users.deleteByUserId(userId);
    }

    /** Count of ADMIN users (for the last-admin guard). */
    public long adminCount() {
        return users.adminCount();
    }

    /** Whether the user exists and has the ADMIN role. */
    public boolean isAdmin(@NonNull UUID userId) {
        return users.isAdmin(userId);
    }

    /** Lists every user (admin only). */
    public @NonNull List<UserEntity> listAll() {
        return users.listAll();
    }

    /** Resets the password (new Argon2id hash; invalidates the existing vault). */
    public void setPassword(@NonNull UUID userId, @NonNull String password) {
        synchronized (auth) {
            auth.logout(userId);
            users.setPassword(userId, password);
        }
    }
}
