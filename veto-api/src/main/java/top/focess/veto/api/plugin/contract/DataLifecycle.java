package top.focess.veto.api.plugin.contract;

import org.jspecify.annotations.NonNull;

/** Required permanent-data deletion, separate from best-effort runtime/logout notifications. */
public interface DataLifecycle {
    /** Called on the deleting transaction's thread. Throwing aborts deletion. */
    @NonNull Completion prepareOwnerDeletion(@NonNull String owner, @NonNull String userId);

    @NonNull Completion prepareSessionDeletion(
            @NonNull String owner, @NonNull String userId, @NonNull String sessionId);

    /** Always delivered after the transaction ends; release reservations on rollback. */
    @FunctionalInterface
    interface Completion {
        void complete(boolean committed);
    }
}
