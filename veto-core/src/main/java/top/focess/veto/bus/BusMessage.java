package top.focess.veto.bus;

import com.fasterxml.jackson.databind.JsonNode;
import org.jspecify.annotations.NonNull;

/** Fixed envelopes for the authenticated WebSocket bus; DAG data remains arbitrary JSON. */
public sealed interface BusMessage {
    @NonNull String type();

    public record Welcome(
            @NonNull String type,
            @NonNull String sessionId,
            @NonNull String timestamp,
            @NonNull String version)
            implements BusMessage {}

    public record Failure(@NonNull String type, @NonNull String message) implements BusMessage {}

    public record SequencedFailure(@NonNull String type, @NonNull String message, long seq)
            implements BusMessage {}

    public record Heartbeat(@NonNull String type, @NonNull JsonNode seq, @NonNull String timestamp)
            implements BusMessage {}

    public record Received(
            @NonNull String type, @NonNull String taskType, long seq, @NonNull String timestamp)
            implements BusMessage {}

    public record DagPayload(
            @NonNull String type,
            @NonNull String source,
            @NonNull String taskType,
            @NonNull JsonNode data,
            @NonNull String timestamp)
            implements BusMessage {}

    public record VetoResult(
            @NonNull String type,
            long seq,
            @NonNull String decision,
            @NonNull String processedPayload,
            @NonNull String reason,
            int redactionCount,
            boolean allowed,
            @NonNull String timestamp)
            implements BusMessage {}

    public record Subscribed(@NonNull String type, @NonNull String topic, @NonNull String timestamp)
            implements BusMessage {}

    public record Unsubscribed(@NonNull String type, @NonNull String timestamp)
            implements BusMessage {}

    public record Echo(
            @NonNull String type, @NonNull String data, long seq, @NonNull String timestamp)
            implements BusMessage {}
}
