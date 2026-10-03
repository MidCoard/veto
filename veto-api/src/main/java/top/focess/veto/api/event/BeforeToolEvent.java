package top.focess.veto.api.event;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.contract.JsonValue;

/**
 * Fired before a validated tool call is authorized and executed. This is a security gate.
 *
 * <p>The decision is monotonic: {@link #decide} only escalates and never relaxes, and {@link
 * Decision#REJECT} also calls {@link Event#prevent()} to stop propagation to later handlers by
 * default. The host enforces that security decision independently of the reversible {@link
 * Cancellable} action flag. Clearing action cancellation cannot relax approval requirements or undo
 * rejection. The producer reads the final action flag after delivery. Cooperative host stop remains
 * independently enforced by the producer and dispatch execution path.
 */
public final class BeforeToolEvent extends WorkflowEvent implements Cancellable {
    /** Approval decision a handler can tighten but never relax. */
    public enum Decision {
        /** Allow evaluation to continue without requesting extra approval. */
        CONTINUE,
        /** Require host approval unless another handler or host policy rejects. */
        REQUIRE_APPROVAL,
        /** Reject the call and halt the chain. */
        REJECT
    }

    /**
     * Immutable tool invocation under evaluation.
     *
     * @param name registered tool name
     * @param callId unique tool-call identity
     * @param arguments validated immutable arguments
     */
    public record Invocation(
            @NonNull String name,
            @NonNull String callId,
            JsonValue.@NonNull ObjectValue arguments) {}

    private final @NonNull Invocation invocation;
    private @NonNull Decision decision = Decision.CONTINUE;
    private boolean cancelled;

    /**
     * Creates the tool-approval event.
     *
     * @param scope authenticated owner, session and agent identity
     * @param invocation immutable tool invocation under evaluation
     */
    public BeforeToolEvent(Scope.@NonNull AgentScope scope, @NonNull Invocation invocation) {
        super(scope);
        this.invocation = invocation;
    }

    /**
     * Returns the invocation under evaluation.
     *
     * @return immutable tool invocation
     */
    public @NonNull Invocation invocation() {
        return invocation;
    }

    /**
     * Returns the current monotonic decision.
     *
     * @return decision so far
     */
    public @NonNull Decision decision() {
        return decision;
    }

    /**
     * Returns whether the producer should cancel the tool action after delivery.
     *
     * @return current reversible action cancellation
     */
    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Sets reversible action cancellation without changing the security decision or propagation.
     *
     * @param cancelled whether the producer should cancel the tool action
     */
    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    /**
     * Escalates the security decision; an equal or lower-severity value is ignored. Rejection also
     * prevents propagation to later handlers by default. Neither flag permits relaxing this
     * decision.
     *
     * @param next this handler's decision
     */
    public void decide(@NonNull Decision next) {
        if (next.ordinal() <= decision.ordinal()) return;
        decision = next;
        if (next == Decision.REJECT) prevent();
    }

    /** Rejects the call and irreversibly halts the chain. */
    public void reject() {
        decide(Decision.REJECT);
    }
}
