package top.focess.veto.api.plugin.contract;

import org.jspecify.annotations.NonNull;

/** Required permanent-data deletion, separate from best-effort runtime/logout notifications. */
public interface DataLifecycle {
    /**
     * Prepares permanent owner deletion on the deleting transaction's thread.
     *
     * @param owner authenticated owner name
     * @param userId immutable user identity
     * @return completion callback to receive the transaction outcome
     */
    @NonNull Completion prepareOwnerDeletion(@NonNull String owner, @NonNull String userId);

    /**
     * Prepares permanent session deletion on the deleting transaction's thread.
     *
     * @param owner authenticated owner name
     * @param userId immutable user identity
     * @param sessionId session being deleted
     * @return completion callback to receive the transaction outcome
     */
    @NonNull Completion prepareSessionDeletion(
            @NonNull String owner, @NonNull String userId, @NonNull String sessionId);

    /** Always delivered after the transaction ends; release reservations on rollback. */
    @FunctionalInterface
    interface Completion {
        /**
         * Releases prepared state after commit or rollback.
         *
         * @param committed whether the deletion transaction committed
         */
        void complete(boolean committed);
    }
}
