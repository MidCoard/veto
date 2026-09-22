package top.focess.veto.plugin.contract;

import org.jspecify.annotations.NonNull;

/**
 * Session-lifecycle notifications. The host invokes these after the corresponding owner, session or
 * agent lifecycle transition; every method defaults to a no-op.
 */
public interface SessionLifecycle {
    default void onOwnerOpen(@NonNull String ownerId) {}

    default void onOwnerClosed(@NonNull String ownerId) {}

    default void onSessionClosed(@NonNull String ownerId, @NonNull String sessionId) {}

    default void onAgentTerminated(
            @NonNull String ownerId, @NonNull String sessionId, @NonNull String agentId) {}
}
