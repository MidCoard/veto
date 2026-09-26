package top.focess.veto.api.event;

import org.jspecify.annotations.NonNull;

/**
 * Base for best-effort owner, session, and agent lifecycle notifications.
 *
 * <p>The host broadcasts a lifecycle event to every active plugin that contributed a {@link
 * Listener} after the corresponding runtime transition. Unlike {@link WorkflowEvent}, it carries no
 * cancellation signal and is never session-selected: observing one grants no authority, and a
 * handler that fails is logged and skipped so it cannot break logout, session deletion, or agent
 * termination.
 */
public abstract class LifecycleEvent extends Event {
    private final @NonNull String owner;

    /**
     * Creates the lifecycle notification for an owner.
     *
     * @param owner authenticated owner identity
     */
    protected LifecycleEvent(@NonNull String owner) {
        this.owner = owner;
    }

    /**
     * Returns the authenticated owner identity.
     *
     * @return owner identity
     */
    public @NonNull String owner() {
        return owner;
    }
}
