package top.focess.veto.api.event;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.Scope;

/**
 * Best-effort notification after a session deletion commits. Required plugin-data cleanup uses a
 * separate transaction participant; this event is for retiring remaining runtime state.
 */
public final class SessionDeletedEvent extends LifecycleEvent {
    private final Scope.@NonNull SessionScope scope;

    /**
     * Creates the notification for one session identity.
     *
     * @param scope deleted session identity
     */
    public SessionDeletedEvent(Scope.@NonNull SessionScope scope) {
        this.scope = scope;
    }

    /**
     * Returns the deleted session identity.
     *
     * @return the deleted session's scope
     */
    @Override
    public Scope.@NonNull SessionScope scope() {
        return scope;
    }
}
