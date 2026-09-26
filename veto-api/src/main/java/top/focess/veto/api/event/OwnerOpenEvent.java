package top.focess.veto.api.event;

import org.jspecify.annotations.NonNull;

/** Notifies that an authenticated owner became active. */
public final class OwnerOpenEvent extends LifecycleEvent {
    /**
     * Creates the owner-opened notification.
     *
     * @param owner authenticated owner identity
     */
    public OwnerOpenEvent(@NonNull String owner) {
        super(owner);
    }
}
