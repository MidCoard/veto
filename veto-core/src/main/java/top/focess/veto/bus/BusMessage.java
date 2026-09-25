package top.focess.veto.bus;

import com.fasterxml.jackson.databind.JsonNode;
import org.jspecify.annotations.NonNull;

/** Fixed envelopes for the authenticated WebSocket bus; DAG data remains arbitrary JSON. */
public sealed interface BusMessage {
    @NonNull String type();

    record Welcome(
            @NonNull String type,
            @NonNull String sessionId,
            @NonNull String timestamp,
            @NonNull String version)
            implements BusMessage {}

    record Failure(@NonNull String type, @NonNull String message) implements BusMessage {}

    record SequencedFailure(@NonNull String type, @NonNull String message, long seq)
            implements BusMessage {}

    record Heartbeat(@NonNull String type, @NonNull JsonNode seq, @NonNull String timestamp)
            implements BusMessage {}

    record Received(
            @NonNull String type, @NonNull String taskType, long seq, @NonNull String timestamp)
            implements BusMessage {}

    record DagPayload(
            @NonNull String type,
            @NonNull String source,
            @NonNull String taskType,
            @NonNull JsonNode data,
            @NonNull String timestamp)
            implements BusMessage {}

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

    record Subscribed(@NonNull String type, @NonNull String topic, @NonNull String timestamp)
            implements BusMessage {}

    record Unsubscribed(@NonNull String type, @NonNull String timestamp) implements BusMessage {}

    record Echo(@NonNull String type, @NonNull String data, long seq, @NonNull String timestamp)
            implements BusMessage {}
}
