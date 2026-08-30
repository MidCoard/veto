package top.focess.veto.session;

import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.NonNull;

/** One append-only event annotated with its effective-history state for the records UI. */
public record SessionRecord(
        @NonNull String agentId,
        int turnNumber,
        @NonNull String type,
        @NonNull Map<String, Object> payload,
        @NonNull Instant timestamp,
        boolean active,
        int rewoundByTurnNumber,
        int rewoundRecords) {

    public SessionRecord {
        payload = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(payload));
    }

    public @NonNull SessionRecord withRewoundRecords(int count) {
        return new SessionRecord(
                agentId, turnNumber, type, payload, timestamp, active, rewoundByTurnNumber, count);
    }

    public @NonNull SessionRecord inactiveAfter(int boundaryTurnNumber) {
        return new SessionRecord(
                agentId,
                turnNumber,
                type,
                payload,
                timestamp,
                false,
                boundaryTurnNumber,
                rewoundRecords);
    }
}
