package top.focess.veto.bus;

import com.fasterxml.jackson.databind.JsonNode;
import org.jspecify.annotations.NonNull;

/** Fixed envelopes for the authenticated WebSocket bus; DAG data remains arbitrary JSON. */
public sealed interface BusMessage {
    /** Discriminator written to the JSON {@code type} field. */
    @NonNull String type();

    /** Sent once after a successful handshake; carries the assigned session id. */
    record Welcome(
            @NonNull String type,
            @NonNull String sessionId,
            @NonNull String timestamp,
            @NonNull String version)
            implements BusMessage {}

    /** Generic error response. */
    record Failure(@NonNull String type, @NonNull String message) implements BusMessage {}

    /** Error response correlated with the sequence of the offending client message. */
    record SequencedFailure(@NonNull String type, @NonNull String message, long seq)
            implements BusMessage {}

    /** Acknowledgment of a client heartbeat; echoes the client's seq or the server's counter. */
    record Heartbeat(@NonNull String type, @NonNull JsonNode seq, @NonNull String timestamp)
            implements BusMessage {}

    /** Acknowledgment that the bus received a DAG payload. */
    record Received(
            @NonNull String type, @NonNull String taskType, long seq, @NonNull String timestamp)
            implements BusMessage {}

    /** DAG payload broadcast to the sender's other authenticated connections. */
    record DagPayload(
            @NonNull String type,
            @NonNull String source,
            @NonNull String taskType,
            @NonNull JsonNode data,
            @NonNull String timestamp)
            implements BusMessage {}

    /** Outcome of a {@code veto.process} request. */
    record VetoResult(
            @NonNull String type,
            long seq,
            @NonNull String decision,
            @NonNull String processedPayload,
            @NonNull String reason,
            int redactionCount,
            boolean allowed,
            @NonNull String timestamp)
            implements BusMessage {}

    /** Confirms a topic subscription. */
    record Subscribed(@NonNull String type, @NonNull String topic, @NonNull String timestamp)
            implements BusMessage {}

    /** Confirms removal of the connection's topic subscription. */
    record Unsubscribed(@NonNull String type, @NonNull String timestamp) implements BusMessage {}

    /** Echoes back the payload of an unrecognized message. */
    record Echo(@NonNull String type, @NonNull String data, long seq, @NonNull String timestamp)
            implements BusMessage {}
}
