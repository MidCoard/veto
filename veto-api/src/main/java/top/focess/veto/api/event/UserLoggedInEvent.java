package top.focess.veto.api.event;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.Scope;

/** Notifies plugins after an existing user successfully logged in. */
public final class UserLoggedInEvent extends UserAuthenticatedEvent {
    /**
     * Creates the login notification.
     *
     * @param scope logged-in user's identity
     */
    public UserLoggedInEvent(Scope.@NonNull UserScope scope) {
        super(scope);
    }
}
