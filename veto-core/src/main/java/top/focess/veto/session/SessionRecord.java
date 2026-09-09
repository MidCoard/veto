package top.focess.veto.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.RecordTokenCounter;

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
        payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    @JsonProperty("tokenCount")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public Long tokenCount() {
        return RecordTokenCounter.count(payload);
    }

    @JsonProperty("usedTokens")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public Long usedTokens() {
        return RecordTokenCounter.count(payload);
    }

    @JsonProperty("tokenCountSource")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public String tokenCountSource() {
        Object value = payload.get("tokenCountSource");
        return value instanceof String text ? text : null;
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
