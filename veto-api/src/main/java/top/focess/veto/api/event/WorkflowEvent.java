package top.focess.veto.api.event;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.Scope;

/**
 * Base for workflow payloads carrying agent identity.
 *
 * <p>Carries the workflow identity. Subclasses add the mutable payload their handlers transform.
 * Observing an event never grants authority. Host authorization remains enforced independently of
 * handler changes; cancellable subclasses retain their reversible action-cancellation flag.
 *
 * <p>The caller may be an agent worker, an input-submitting thread, or an admitted tool caller.
 * Handlers complete before the producer reads the transformed event; no event executor or
 * cross-dispatch serialization is implied. The confinement contract of {@link Event} applies.
 */
public abstract class WorkflowEvent extends Event {
    private final Scope.@NonNull AgentScope scope;

    /**
     * Creates a workflow payload; the host invocation context determines delivery recipients.
     *
     * @param scope authenticated owner, session and agent identity
     */
    protected WorkflowEvent(Scope.@NonNull AgentScope scope) {
        this.scope = scope;
    }

    /**
     * Returns the authenticated owner, session and agent identity.
     *
     * @return workflow agent scope
     */
    @Override
    public Scope.@NonNull AgentScope scope() {
        return scope;
    }
}
