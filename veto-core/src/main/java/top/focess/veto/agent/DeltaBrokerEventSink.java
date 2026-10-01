package top.focess.veto.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import top.focess.veto.bus.DeltaBroker;
import top.focess.veto.bus.DeltaFrame;

/**
 * Inline adapter that publishes into the configured session broker with this agent's identity.
 *
 * <p>Each call copies the frame's attribute map and replaces agentId; nested JSON nodes remain
 * shared and read-only. This adapter owns no executor, lock, broker lifecycle, or delivery queue.
 * Concurrent publication follows the broker's contract; it does not establish callback order.
 */
public final class DeltaBrokerEventSink implements AgentEventSink {
    private final @NonNull String agentId;
    private final @NonNull UUID sessionId;
    private final DeltaBroker broker;

    /**
     * @param broker the session broker to publish to; {@code null} disables publication entirely
     */
    public DeltaBrokerEventSink(
            @NonNull String agentId, @NonNull UUID sessionId, DeltaBroker broker) {
        this.agentId = agentId;
        this.sessionId = sessionId;
        this.broker = broker;
    }

    @Override
    public void publish(@NonNull DeltaFrame frame) {
        if (broker == null) return;
        Map<String, JsonNode> attributes = new HashMap<>(frame.attrs());
        attributes.put("agentId", TextNode.valueOf(agentId));
        broker.publish(
                new DeltaFrame(
                        sessionId,
                        frame.sequence(),
                        frame.emittedAt(),
                        frame.kind(),
                        frame.text(),
                        attributes));
    }
}
