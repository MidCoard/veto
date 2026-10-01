package top.focess.veto.api.event;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.Scope;

/** Notifies that one agent reached terminal execution. */
public final class AgentTerminatedEvent extends LifecycleEvent {
    private final Scope.@NonNull AgentScope scope;

    /**
     * Creates the notification for one agent identity.
     *
     * @param scope terminated agent identity
     */
    public AgentTerminatedEvent(Scope.@NonNull AgentScope scope) {
        this.scope = scope;
    }

    /**
     * Returns the terminated agent identity.
     *
     * @return the terminated agent's scope
     */
    @Override
    public Scope.@NonNull AgentScope scope() {
        return scope;
    }
}
