package top.focess.veto.vault;

import static top.focess.veto.util.LogValues.safe;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.focess.veto.agent.continuation.RequestContinuationStore;
import top.focess.veto.agent.intercept.HitlRecordRepository;
import top.focess.veto.api.event.BeforeUserRegisterEvent;
import top.focess.veto.api.event.SessionDeletedEvent;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.event.EventManager;
import top.focess.veto.i18n.Msg;
import top.focess.veto.integration.plugins.PluginDataCleanup;
import top.focess.veto.integration.plugins.storage.ScopedPluginStorage;
import top.focess.veto.model.AgentInstanceRepository;
import top.focess.veto.model.AgentPatternRepository;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.model.SessionRepository;

/**
 * Admin operations on user accounts: provisioning, deletion (with cascade), password reset, and
 * role/lookup helpers. Backs the {@code /user} commands.
 *
 * <p>Deletion cascades across owned database rows, then removes the vault after successful commit.
 * This prevents orphan rows on username reuse while preserving secrets if deletion rolls back.
 */
@Service
public class UserAdminService {
    private final @NonNull EventManager eventManager;
    private final @NonNull PluginDataCleanup pluginDataCleanup;
    private final @NonNull ScopedPluginStorage pluginStorage;
    private final @NonNull HitlRecordRepository hitlRecords;
    private final @NonNull RequestContinuationStore continuations;

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.vault.UserAdminService");

    private final @NonNull UserRegistry users;
    private final @NonNull AgentPatternRepository patterns;
    private final @NonNull SessionRepository sessions;
    private final @NonNull AgentInstanceRepository agents;
    private final @NonNull KeysteadVault vault;
    private final @NonNull AuthLifecycleManager auth;
    private final @NonNull TransactionTemplate accountTransactions;

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
            @NonNull RequestContinuationStore continuations,
            @NonNull PlatformTransactionManager transactions) {
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
        this.accountTransactions = new TransactionTemplate(transactions);
        this.accountTransactions.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Provisions a new account: creates the user row and a <em>closed</em> keystead vault (not
     * unlocked - the user opens it on first login). The admin's own session/vault is untouched.
     */
    public @NonNull UserEntity create(
            @NonNull String username, @NonNull String password, @NonNull String role) {
        try (var _ = auth.locks().shared()) {
            var decision = new BeforeUserRegisterEvent(username, role);
            eventManager.submit(decision);
            if (decision.isCancelled())
                throw new AuthException(
                        AuthException.Kind.FORBIDDEN, Msg.get("error.auth.registrationCancelled"));
            var created =
                    accountTransactions.execute(
                            status -> {
                                var account = users.create(username, password, role);
                                vault.createVault(account.getUserId(), password);
                                return account;
                            });
            if (created == null)
                throw new IllegalStateException("Account provisioning returned no account");
            return created;
        }
    }

    /**
     * Detaches runtime authentication and deletes owned database rows in one transaction. Vault
     * files are removed only after a successful commit; file cleanup is best-effort.
     */
    public void deleteUser(@NonNull UUID userId) {
        try (var _ = auth.locks().exclusive()) {
            if (users.findByUserId(userId).isEmpty())
                throw new IllegalArgumentException("User not found: " + userId);
            var deleteStore = vault.prepareStoreDeletion(userId);
            accountTransactions.executeWithoutResult(status -> deleteUserInTransaction(userId));
            // File deletion cannot be rolled back; run only after the database commit succeeds.
            deleteStore.run();
        }
    }

    private void deleteUserInTransaction(@NonNull UUID userId) {
        if (users.isAdmin(userId) && users.adminCount() <= 1) {
            throw new IllegalArgumentException("Cannot delete the last administrator account.");
        }
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
        users.deleteByUserId(userId);
    }

    /** Lists every user (admin only). */
    public @NonNull List<UserEntity> listAll() {
        return users.listAll();
    }

    /**
     * Changes an unlocked vault and account password together, preserving credential identities.
     */
    public void setPassword(@NonNull UUID userId, @NonNull String password) {
        try (var _ = auth.locks().account(userId)) {
            if (users.findByUserId(userId).isEmpty())
                throw new IllegalArgumentException("User not found: " + userId);
            if (password.isEmpty()) throw new IllegalArgumentException("Password cannot be empty.");
            accountTransactions.executeWithoutResult(
                    status -> {
                        vault.changePassword(userId, password);
                        TransactionSynchronizationManager.registerSynchronization(
                                new TransactionSynchronization() {
                                    @Override
                                    public void afterCompletion(int completion) {
                                        // An uncertain commit may already have changed the
                                        // password.
                                        if (completion == STATUS_UNKNOWN) auth.logout(userId);
                                    }
                                });
                        users.setPassword(userId, password);
                    });
            auth.logout(userId);
        }
    }
}
