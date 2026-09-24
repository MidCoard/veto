package top.focess.veto.integration.plugins;

import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.contract.DataLifecycle;
import top.focess.veto.api.plugin.contract.PluginFailure;
import top.focess.veto.api.plugin.contract.SessionLifecycle;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.plugin.runtime.*;
import top.focess.veto.vault.UserRegistry;

/**
 * Dispatches best-effort runtime transitions and required permanent-data deletion preparation.
 * Runtime notifications never break logout. Permanent deletion preparation runs in the host
 * transaction and fails closed when an installed contributor is unavailable.
 */
@Service
public class PluginLifecycleEvents {
    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.integration.plugins.PluginLifecycleEvents");

    private final @NonNull PluginManager manager;

    private @Nullable UserRegistry users;

    @Autowired
    public void attachUsers(@NonNull UserRegistry users) {
        this.users = users;
    }

    public void beforeOwnerDeleted(@NonNull String owner) {
        if (manager.catalog().entries(StandardContributionPoints.DATA_LIFECYCLE).isEmpty()) return;
        String identity = userIdentity(owner);
        requiredDeletion(lifecycle -> lifecycle.prepareOwnerDeletion(owner, identity));
    }

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

    public PluginLifecycleEvents(@NonNull PluginManager manager) {
        this.manager = manager;
    }

    public void ownerOpened(@NonNull String ownerId) {
        dispatch(lifecycle -> lifecycle.onOwnerOpen(ownerId));
    }

    public void ownerClosed(@NonNull String ownerId) {
        dispatch(lifecycle -> lifecycle.onOwnerClosed(ownerId));
    }

    public void sessionClosed(@NonNull String ownerId, @NonNull String sessionId) {
        dispatch(lifecycle -> lifecycle.onSessionClosed(ownerId, sessionId));
    }

    public void agentTerminated(
            @NonNull String ownerId, @NonNull String sessionId, @NonNull String agentId) {
        dispatch(lifecycle -> lifecycle.onAgentTerminated(ownerId, sessionId, agentId));
    }

    private void dispatch(@NonNull Consumer<@NonNull SessionLifecycle> notification) {
        for (var entry : manager.catalog().entries(StandardContributionPoints.SESSION_LIFECYCLE)) {
            var plugin = manager.plugin(entry.source().namespace());
            if (plugin.state() != PluginState.ACTIVE) continue;
            try {
                plugin.execute(
                        () -> {
                            notification.accept(entry.implementation());
                            return true;
                        });
            } catch (PluginFailure | RuntimeException failure) {
                // Best-effort: a failing plugin must never break logout, session deletion, or
                // agent termination. Runtime exceptions from the notification itself are included.
                log.warn(
                        "Plugin lifecycle notification failed for {}",
                        plugin.identity().id(),
                        failure);
            }
        }
    }
}
