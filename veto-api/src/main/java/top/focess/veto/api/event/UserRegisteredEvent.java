package top.focess.veto.api.event;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.Scope;

/** Notifies plugins after successful signup created and authenticated a new user. */
public final class UserRegisteredEvent extends UserAuthenticatedEvent {
    /**
     * Creates the registration notification.
     *
     * @param scope registered user's identity
     */
    public UserRegisteredEvent(Scope.@NonNull UserScope scope) {
        super(scope);
    }
}
