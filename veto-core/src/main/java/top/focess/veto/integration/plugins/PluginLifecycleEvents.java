package top.focess.veto.integration.plugins;

import java.util.function.Function;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.focess.veto.api.event.AgentTerminatedEvent;
import top.focess.veto.api.event.Event;
import top.focess.veto.api.event.SessionDeletedEvent;
import top.focess.veto.api.event.UserLoggedInEvent;
import top.focess.veto.api.event.UserLogoutEvent;
import top.focess.veto.api.event.UserRegisteredEvent;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.contract.DataLifecycle;
import top.focess.veto.api.plugin.contract.PluginFailure;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.vault.UserRegistry;

/**
 * Dispatches best-effort runtime transitions and required permanent-data deletion preparation.
 * Runtime notifications isolate nonfatal listener failures; fatal VM errors and thread death
 * propagate. Permanent deletion preparation runs in the host transaction and fails closed when an
 * installed contributor is unavailable.
 *
 * <p>Notifications run inline on authentication/logout callers, committed-deletion transaction
 * callbacks, or agent termination callers. Each call creates its own event and completes its
 * prepared recipient route before returning. Separate callers may dispatch concurrently; this
 * service owns neither a notification queue nor global transition ordering. Required deletion
 * completion runs in the host transaction's afterCompletion callback rather than as an event.
 */
@Service
public class PluginLifecycleEvents {
    private final @NonNull PluginManager manager;

    private UserRegistry users;

    /** Attaches the user registry used to resolve permanent-deletion identities. */
    @Autowired
    public void attachUsers(@NonNull UserRegistry users) {
        this.users = users;
    }

    /** Required owner-deletion preparation; fails closed when a contributor is unavailable. */
    public void beforeOwnerDeleted(@NonNull String owner) {
        if (manager.hasInactiveDataLifecycle())
            throw new IllegalStateException("Plugin data cleanup is unavailable");
        if (manager.catalog().entries(StandardContributionPoints.DATA_LIFECYCLE).isEmpty()) return;
        String identity = userIdentity(owner);
        requiredDeletion(lifecycle -> lifecycle.prepareOwnerDeletion(owner, identity));
    }

    /** Required session-deletion preparation; fails closed when a contributor is unavailable. */
    public void beforeSessionDeleted(@NonNull String owner, @NonNull String session) {
        if (manager.hasInactiveDataLifecycle())
            throw new IllegalStateException("Plugin data cleanup is unavailable");
        if (manager.catalog().entries(StandardContributionPoints.DATA_LIFECYCLE).isEmpty()) return;
        String identity = userIdentity(owner);
        requiredDeletion(lifecycle -> lifecycle.prepareSessionDeletion(owner, identity, session));
    }

    private @NonNull String userIdentity(@NonNull String owner) {
        var registry = users;
        if (registry == null)
            throw new IllegalStateException("Deletion identity service unavailable");
        return registry.findByUsername(owner)
                .orElseThrow(() -> new IllegalStateException("Account no longer exists"))
                .storageIdentity();
    }

    private void requiredDeletion(
            @NonNull Function<@NonNull DataLifecycle, DataLifecycle.@NonNull Completion> prepare) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("Permanent data deletion requires a transaction");
        for (var entry : manager.catalog().entries(StandardContributionPoints.DATA_LIFECYCLE)) {
            String owner = entry.source().namespace();
            var plugin = manager.beginDataCleanup(owner);
            try {
                var completion = plugin.execute(() -> prepare.apply(entry.implementation()));
                TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronization() {
                            @Override
                            public void afterCompletion(int status) {
                                try {
                                    completion.complete(
                                            status == TransactionSynchronization.STATUS_COMMITTED);
                                } finally {
                                    manager.endDataCleanup(owner);
                                }
                            }
                        });
            } catch (PluginFailure | RuntimeException failure) {
                manager.endDataCleanup(owner);
                throw new IllegalStateException("Plugin data cleanup is unavailable", failure);
            }
        }
    }

    /** Creates the dispatcher over the installed plugin catalog. */
    public PluginLifecycleEvents(@NonNull PluginManager manager) {
        this.manager = manager;
    }

    /** Best-effort notification after signup created and authenticated a user. */
    public void userRegistered(@NonNull String ownerId) {
        broadcast(new UserRegisteredEvent(new Scope.UserScope(ownerId)));
    }

    /** Best-effort notification after an existing user logged in. */
    public void userLoggedIn(@NonNull String ownerId) {
        broadcast(new UserLoggedInEvent(new Scope.UserScope(ownerId)));
    }

    /** Best-effort notification when unified user logout begins. */
    public void userLogout(@NonNull String ownerId) {
        broadcast(new UserLogoutEvent(new Scope.UserScope(ownerId)));
    }

    /** Best-effort notification after session deletion commits. */
    public void sessionDeleted(@NonNull String ownerId, @NonNull String sessionId) {
        broadcast(new SessionDeletedEvent(new Scope.SessionScope(ownerId, sessionId)));
    }

    /** Best-effort notification that a session agent terminated. */
    public void agentTerminated(
            @NonNull String ownerId, @NonNull String sessionId, @NonNull String agentId) {
        broadcast(new AgentTerminatedEvent(new Scope.AgentScope(ownerId, sessionId, agentId)));
    }

    /**
     * Broadcasts a lifecycle notification to every active listener. The registry logs and skips a
     * nonfatal handler failure; fatal VM errors and thread death propagate.
     */
    private void broadcast(@NonNull Event event) {
        manager.events().broadcast(event);
    }
}
