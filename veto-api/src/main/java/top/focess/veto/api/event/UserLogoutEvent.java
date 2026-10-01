package top.focess.veto.api.event;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.contract.DataLifecycle;

/**
 * Notifies plugins when unified user logout begins, before terminal detachment and vault closure.
 * Permanent data is retained; required deletion stays on {@link DataLifecycle}. This best-effort
 * notification does not confirm that later logout steps succeeded.
 */
public final class UserLogoutEvent extends LifecycleEvent {
    private final Scope.@NonNull UserScope scope;

    /**
     * Creates the notification for one user identity.
     *
     * @param scope logging-out user identity
     */
    public UserLogoutEvent(Scope.@NonNull UserScope scope) {
        this.scope = scope;
    }

    /**
     * Returns the logging-out user identity.
     *
     * @return the logging-out user's scope
     */
    @Override
    public Scope.@NonNull UserScope scope() {
        return scope;
    }
}
