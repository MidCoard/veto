package top.focess.veto.api.event;

import org.jspecify.annotations.NonNull;

/**
 * Notifies that an owner runtime closed. Permanent data is not implicitly deleted; required
 * deletion stays on {@link top.focess.veto.api.plugin.contract.DataLifecycle}.
 */
public final class OwnerClosedEvent extends LifecycleEvent {
    /**
     * Creates the owner-closed notification.
     *
     * @param owner authenticated owner identity
     */
    public OwnerClosedEvent(@NonNull String owner) {
        super(owner);
    }
}
