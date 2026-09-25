package top.focess.veto.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.RecordTokenCounter;
import top.focess.veto.agent.RecordUsage;
import top.focess.veto.agent.UsageMeasurement;

/** One append-only event annotated with its effective-history state for the records UI. */
public record SessionRecord(
        @NonNull String agentId,
        int turnNumber,
        @NonNull String type,
        @NonNull Map<String, Object> payload,
        @NonNull Instant timestamp,
        boolean active,
        int rewoundByTurnNumber,
        int rewoundRecords,
        @NonNull List<UsageMeasurement> llmUsage) {

    /** Convenience constructor that decodes {@code llmUsage} from the payload's usage metadata. */
    public SessionRecord(
            @NonNull String agentId,
            int turnNumber,
            @NonNull String type,
            @NonNull Map<String, Object> payload,
            @NonNull Instant timestamp,
            boolean active,
            int rewoundByTurnNumber,
            int rewoundRecords) {
        this(
                agentId,
                turnNumber,
                type,
                payload,
                timestamp,
                active,
                rewoundByTurnNumber,
                rewoundRecords,
                RecordUsage.decode(payload.get("llmUsage")));
    }

    /** Copies {@code llmUsage} defensively and strips usage metadata from the payload. */
    public SessionRecord {
        llmUsage = List.copyOf(llmUsage);
        var content = new LinkedHashMap<>(payload);
        content.remove("llmUsage");
        content.remove("usageCheckpoint");
        payload = content;
        payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    /** Estimated token count of the record's payload, always serialized for the records UI. */
    @JsonProperty("tokenCount")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public Long tokenCount() {
        return RecordTokenCounter.count(payload);
    }

    /** Alias of {@link #tokenCount()}, serialized under the {@code usedTokens} field name. */
    @JsonProperty("usedTokens")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public Long usedTokens() {
        return RecordTokenCounter.count(payload);
    }

    /** The token-count source recorded in the payload, or null when the payload carries none. */
    @JsonProperty("tokenCountSource")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public String tokenCountSource() {
        Object value = payload.get("tokenCountSource");
        return value instanceof String text ? text : null;
    }

    /** A copy of this record annotated with how many records its rewind removed. */
    public @NonNull SessionRecord withRewoundRecords(int count) {
        return new SessionRecord(
                agentId,
                turnNumber,
                type,
                payload,
                timestamp,
                active,
                rewoundByTurnNumber,
                count,
                llmUsage);
    }

    /**
     * A copy of this record marked inactive, rewound by the given boundary turn number (the rewind
     * that removed it from effective history).
     */
    public @NonNull SessionRecord inactiveAfter(int boundaryTurnNumber) {
        return new SessionRecord(
                agentId,
                turnNumber,
                type,
                payload,
                timestamp,
                false,
                boundaryTurnNumber,
                rewoundRecords,
                llmUsage);
    }
}
