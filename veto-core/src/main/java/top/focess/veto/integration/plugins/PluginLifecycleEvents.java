package top.focess.veto.integration.plugins;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.focess.veto.api.event.AgentTerminatedEvent;
import top.focess.veto.api.event.Event;
import top.focess.veto.api.event.OwnerClosedEvent;
import top.focess.veto.api.event.OwnerOpenEvent;
import top.focess.veto.api.event.SessionClosedEvent;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.contract.DataLifecycle;
import top.focess.veto.api.plugin.contract.PluginFailure;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.vault.UserRegistry;

/**
 * Dispatches best-effort runtime transitions and required permanent-data deletion preparation.
 * Runtime notifications never break logout. Permanent deletion preparation runs in the host
 * transaction and fails closed when an installed contributor is unavailable.
 */
@Service
public class PluginLifecycleEvents {
    private final @NonNull PluginManager manager;

    @SuppressWarnings(
            "NullableProblems") // WHY: lazily attached and no package @DefaultQualifier, so
    // NullnessChecker needs this @Nullable
    private @Nullable UserRegistry users;

    /** Attaches the user registry used to resolve permanent-deletion identities. */
    @Autowired
    public void attachUsers(@NonNull UserRegistry users) {
        this.users = users;
    }

    /** Required owner-deletion preparation; fails closed when a contributor is unavailable. */
    public void beforeOwnerDeleted(@NonNull String owner) {
        if (manager.catalog().entries(StandardContributionPoints.DATA_LIFECYCLE).isEmpty()) return;
        String identity = userIdentity(owner);
        requiredDeletion(lifecycle -> lifecycle.prepareOwnerDeletion(owner, identity));
    }

    /** Required session-deletion preparation; fails closed when a contributor is unavailable. */
    public void beforeSessionDeleted(@NonNull String owner, @NonNull String session) {
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
            var plugin = manager.plugin(entry.source().namespace());
            try {
                var completion = plugin.execute(() -> prepare.apply(entry.implementation()));
                TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronization() {
                            @Override
                            public void afterCompletion(int status) {
                                completion.complete(
                                        status == TransactionSynchronization.STATUS_COMMITTED);
                            }
                        });
            } catch (PluginFailure failure) {
                throw new IllegalStateException("Plugin data cleanup is unavailable", failure);
            }
        }
    }

    /** Creates the dispatcher over the installed plugin catalog. */
    public PluginLifecycleEvents(@NonNull PluginManager manager) {
        this.manager = manager;
    }

    /** Best-effort notification that the owner's vault was opened. */
    public void ownerOpened(@NonNull String ownerId) {
        broadcast(new OwnerOpenEvent(ownerId));
    }

    /** Best-effort notification that the owner's vault was closed. */
    public void ownerClosed(@NonNull String ownerId) {
        broadcast(new OwnerClosedEvent(ownerId));
    }

    /** Best-effort notification that a session ended. */
    public void sessionClosed(@NonNull String ownerId, @NonNull String sessionId) {
        broadcast(new SessionClosedEvent(ownerId, sessionId));
    }

    /** Best-effort notification that a session agent terminated. */
    public void agentTerminated(
            @NonNull String ownerId, @NonNull String sessionId, @NonNull String agentId) {
        broadcast(new AgentTerminatedEvent(ownerId, sessionId, agentId));
    }

    /**
     * Broadcasts a lifecycle notification to every active listener. The registry logs and skips a
     * failing handler, so one plugin can never break logout, session deletion, or agent
     * termination.
     */
    private void broadcast(@NonNull Event event) {
        if (manager.catalog().entries(StandardContributionPoints.LISTENERS).isEmpty()) return;
        Set<String> active = new HashSet<>();
        for (var entry : manager.catalog().entries(StandardContributionPoints.LISTENERS)) {
            String namespace = entry.source().namespace();
            if (manager.plugin(namespace).state() == PluginState.ACTIVE) active.add(namespace);
        }
        manager.events().broadcast(event, active);
    }
}
