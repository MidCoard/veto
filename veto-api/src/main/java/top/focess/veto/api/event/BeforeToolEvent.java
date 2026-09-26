package top.focess.veto.api.event;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.contract.Cancellation;
import top.focess.veto.api.plugin.contract.JsonValue;

/**
 * Fired before a validated tool call is authorized and executed. This is a security gate.
 *
 * <p>The decision is monotonic: {@link #decide} only escalates and never relaxes, and {@link
 * Decision#REJECT} also calls {@link Event#prevent()} so the chain halts irreversibly and no later
 * handler can overturn the veto. This event is intentionally not {@link Cancellable}, because a
 * reversible cancel flag would let a downstream plugin un-reject a call.
 */
public final class BeforeToolEvent extends WorkflowEvent {
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

    /**
     * Creates the tool-approval event.
     *
     * @param owner authenticated owner, or {@code null} when unavailable
     * @param sessionId current session identity
     * @param agentId current agent identity
     * @param cancellation cooperative cancellation signal
     * @param invocation immutable tool invocation under evaluation
     */
    public BeforeToolEvent(
            String owner,
            @NonNull String sessionId,
            @NonNull String agentId,
            @NonNull Cancellation cancellation,
            @NonNull Invocation invocation) {
        super(owner, sessionId, agentId, cancellation);
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
     * Escalates the decision; an equal or lower-severity value is ignored. Rejecting also prevents
     * the remaining chain so the veto cannot be overturned.
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
