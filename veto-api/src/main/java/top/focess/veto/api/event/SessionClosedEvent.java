package top.focess.veto.api.event;

import org.jspecify.annotations.NonNull;

/** Notifies that a session runtime closed. Permanent data is not implicitly deleted. */
public final class SessionClosedEvent extends LifecycleEvent {
    private final @NonNull String sessionId;

    /**
     * Creates the session-closed notification.
     *
     * @param owner authenticated owner identity
     * @param sessionId closed session identity
     */
    public SessionClosedEvent(@NonNull String owner, @NonNull String sessionId) {
        super(owner);
        this.sessionId = sessionId;
    }

    /**
     * Returns the closed session identity.
     *
     * @return session identity
     */
    public @NonNull String sessionId() {
        return sessionId;
    }
}
