package top.focess.veto.integration.plugins;

import java.util.function.Function;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.focess.veto.api.plugin.contract.DataLifecycle;
import top.focess.veto.api.plugin.contract.PluginFailure;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.vault.UserRegistry;

/**
 * Required permanent-data cleanup coordinated with the deleting host transaction. Preparation fails
 * closed; completion receives the committed/rolled-back outcome. Concurrent transactions hold
 * independent contributor claims. Plugin preparation runs under live admission, and completion
 * releases its claim in finally on the transaction thread.
 */
@Service
public final class PluginDataCleanup {
    private final @NonNull PluginManager manager;

    public PluginDataCleanup(@NonNull PluginManager manager) {
        this.manager = manager;
    }

    private UserRegistry users;

    /** Attaches the user registry used to resolve permanent-deletion identities. */
    @Autowired
    public void attachUsers(@NonNull UserRegistry users) {
        this.users = users;
    }

    /** Required owner-deletion preparation; fails closed when a contributor is unavailable. */
    public void beforeOwnerDeleted(@NonNull String owner) {
        var publication = manager.snapshot();
        if (manager.hasInactiveDataLifecycle())
            throw new IllegalStateException("Plugin data cleanup is unavailable");
        if (publication.catalog().entries(StandardContributionPoints.DATA_LIFECYCLE).isEmpty())
            return;
        String identity = userIdentity(owner);
        requiredDeletion(publication, lifecycle -> lifecycle.prepareOwnerDeletion(owner, identity));
    }

    /** Required session-deletion preparation; fails closed when a contributor is unavailable. */
    public void beforeSessionDeleted(@NonNull String owner, @NonNull String session) {
        var publication = manager.snapshot();
        if (manager.hasInactiveDataLifecycle())
            throw new IllegalStateException("Plugin data cleanup is unavailable");
        if (publication.catalog().entries(StandardContributionPoints.DATA_LIFECYCLE).isEmpty())
            return;
        String identity = userIdentity(owner);
        requiredDeletion(
                publication,
                lifecycle -> lifecycle.prepareSessionDeletion(owner, identity, session));
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
            PluginManager.@NonNull PublishedState publication,
            @NonNull Function<@NonNull DataLifecycle, DataLifecycle.@NonNull Completion> prepare) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("Permanent data deletion requires a transaction");
        for (var entry : publication.catalog().entries(StandardContributionPoints.DATA_LIFECYCLE)) {
            String owner = entry.source().namespace();
            var plugin = manager.beginDataCleanup(publication.plugin(owner));
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
}
