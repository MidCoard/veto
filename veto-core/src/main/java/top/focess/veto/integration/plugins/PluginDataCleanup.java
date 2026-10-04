package top.focess.veto.integration.plugins;

import java.util.function.Function;
import org.jspecify.annotations.NonNull;
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
 * independent contributor claims that manager shutdown drains before closing activations. Plugin
 * preparation runs under live admission; completion runs on the transaction thread while its claim
 * keeps the participant alive, and releases that claim in finally. This service stores no per-call
 * state; callers must complete their transaction before closing the manager on that same thread.
 */
@Service
public final class PluginDataCleanup {
    private final @NonNull PluginManager manager;
    private final @NonNull UserRegistry users;

    public PluginDataCleanup(@NonNull PluginManager manager, @NonNull UserRegistry users) {
        this.manager = manager;
        this.users = users;
    }

    /** Required owner-deletion preparation; fails closed when a contributor is unavailable. */
    public void beforeOwnerDeleted(@NonNull String owner) {
        var publication = manager.registry();
        if (manager.hasInactiveDataLifecycle())
            throw new IllegalStateException("Plugin data cleanup is unavailable");
        if (publication.entries(StandardContributionPoints.DATA_LIFECYCLE).isEmpty()) return;
        String identity = userIdentity(owner);
        requiredDeletion(publication, lifecycle -> lifecycle.prepareOwnerDeletion(owner, identity));
    }

    /** Required session-deletion preparation; fails closed when a contributor is unavailable. */
    public void beforeSessionDeleted(@NonNull String owner, @NonNull String session) {
        var publication = manager.registry();
        if (manager.hasInactiveDataLifecycle())
            throw new IllegalStateException("Plugin data cleanup is unavailable");
        if (publication.entries(StandardContributionPoints.DATA_LIFECYCLE).isEmpty()) return;
        String identity = userIdentity(owner);
        requiredDeletion(
                publication,
                lifecycle -> lifecycle.prepareSessionDeletion(owner, identity, session));
    }

    private @NonNull String userIdentity(@NonNull String owner) {
        return users.findByUsername(owner)
                .orElseThrow(() -> new IllegalStateException("Account no longer exists"))
                .storageIdentity();
    }

    @SuppressWarnings("removal") // ThreadDeath remains a fatal participant signal while supported.
    private void requiredDeletion(
            @NonNull PluginRegistry publication,
            @NonNull Function<@NonNull DataLifecycle, DataLifecycle.@NonNull Completion> prepare) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("Permanent data deletion requires a transaction");
        for (var entry : publication.entries(StandardContributionPoints.DATA_LIFECYCLE)) {
            String owner = entry.source().namespace();
            var plugin = manager.beginDataCleanup(publication.plugin(owner));
            boolean registered = false;
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
                registered = true;
            } catch (VirtualMachineError | ThreadDeath fatal) {
                throw fatal;
            } catch (PluginFailure | RuntimeException | Error failure) {
                throw new IllegalStateException("Plugin data cleanup is unavailable", failure);
            } finally {
                if (!registered) manager.endDataCleanup(owner);
            }
        }
    }
}
