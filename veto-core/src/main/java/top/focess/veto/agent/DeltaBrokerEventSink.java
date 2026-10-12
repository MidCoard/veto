package top.focess.veto.agent;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.bus.DeltaBroker;
import top.focess.veto.contract.Frame;

/**
 * Inline adapter that publishes into the configured session broker with this agent's identity.
 *
 * <p>Each call verifies the producing agent and session before publication. This adapter owns no
 * executor, lock, broker lifecycle, or delivery queue. Concurrent publication follows the broker's
 * contract; it does not establish callback order.
 */
public final class DeltaBrokerEventSink implements AgentEventSink {
    private final Scope.@NonNull AgentScope scope;
    private final DeltaBroker broker;

    /**
     * @param broker the session broker to publish to; {@code null} disables publication entirely
     */
    public DeltaBrokerEventSink(Scope.@NonNull AgentScope scope, DeltaBroker broker) {
        this.scope = scope;
        this.broker = broker;
    }

    @Override
    public void publish(Frame.@NonNull ScopedFrame frame) {
        if (broker == null) return;
        boolean owned =
                frame instanceof Frame.Action
                        ? scope.sessionScope().equals(frame.scope())
                        : scope.equals(frame.scope());
        if (!owned) throw new IllegalArgumentException("Frame does not belong to this agent");
        broker.publish(frame);
    }
}
