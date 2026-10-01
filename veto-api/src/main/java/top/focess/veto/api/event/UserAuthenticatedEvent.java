package top.focess.veto.api.event;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.Scope;

/**
 * Shared type for successful registration and login notifications. Subscribe to this type for
 * behavior common to both; subscribe to {@link UserRegisteredEvent} or {@link UserLoggedInEvent}
 * when the distinction matters. The host emits only the concrete subevents.
 */
public abstract sealed class UserAuthenticatedEvent extends LifecycleEvent
        permits UserRegisteredEvent, UserLoggedInEvent {
    private final Scope.@NonNull UserScope scope;

    /**
     * Creates the notification for one user identity.
     *
     * @param scope authenticated user identity
     */
    protected UserAuthenticatedEvent(Scope.@NonNull UserScope scope) {
        this.scope = scope;
    }

    /**
     * Returns the authenticated user identity.
     *
     * @return the authenticated user's scope
     */
    @Override
    public Scope.@NonNull UserScope scope() {
        return scope;
    }
}
