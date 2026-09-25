package top.focess.veto.api.plugin.contract;

import org.jspecify.annotations.NonNull;

/**
 * Session-lifecycle notifications. The host invokes these after the corresponding owner, session or
 * agent lifecycle transition; every method defaults to a no-op.
 */
public interface SessionLifecycle {
    /**
     * Notifies that an authenticated owner became active.
     *
     * @param ownerId authenticated owner identity
     */
    default void onOwnerOpen(@NonNull String ownerId) {}

    /**
     * Notifies that an owner runtime closed; permanent data is not implicitly deleted.
     *
     * @param ownerId authenticated owner identity
     */
    default void onOwnerClosed(@NonNull String ownerId) {}

    /**
     * Notifies that a session runtime closed; permanent data is not implicitly deleted.
     *
     * @param ownerId authenticated owner identity
     * @param sessionId closed session identity
     */
    default void onSessionClosed(@NonNull String ownerId, @NonNull String sessionId) {}

    /**
     * Notifies that one agent reached terminal execution.
     *
     * @param ownerId authenticated owner identity
     * @param sessionId containing session identity
     * @param agentId terminated agent identity
     */
    default void onAgentTerminated(
            @NonNull String ownerId, @NonNull String sessionId, @NonNull String agentId) {}
}
