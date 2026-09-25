package top.focess.veto.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.jspecify.annotations.*;

/** Accounting is record metadata, never model message content. */
public final class RecordUsage {
    private static final @NonNull ObjectMapper JSON = new ObjectMapper();

    private RecordUsage() {}

    /**
     * Coerces an already-parsed metadata value into usage measurements ({@code null} yields none).
     */
    public static @NonNull List<UsageMeasurement> decode(Object value) {
        if (value == null) return List.of();
        return JSON.convertValue(value, new TypeReference<List<UsageMeasurement>>() {});
    }

    /**
     * Parses usage measurements from their persisted JSON form.
     *
     * @throws IllegalStateException if the JSON is present but malformed
     */
    public static @NonNull List<UsageMeasurement> read(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return JSON.readValue(json, new TypeReference<List<UsageMeasurement>>() {});
        } catch (Exception failure) {
            throw new IllegalStateException("Invalid request usage metadata", failure);
        }
    }

    /** Returns a copy of {@code turn} with {@code usage} appended to its measurement list. */
    public static @NonNull TurnRecord add(
            @NonNull TurnRecord turn, @NonNull UsageMeasurement usage) {
        turn = RecordTokenCounter.withoutEstimate(turn);
        var values = new ArrayList<>(turn.llmUsage());
        values.add(usage);
        return new TurnRecord(
                turn.turnNumber(), turn.type(), turn.payload(), turn.timestamp(), values);
    }

    /**
     * Folds legacy standalone {@code TOKEN_USAGE} records back onto the content turn they measure,
     * dropping estimates, so accounting rides as record metadata rather than as separate turns.
     */
    public static @NonNull List<TurnRecord> contentRecords(@NonNull List<TurnRecord> raw) {
        List<TurnRecord> result = new ArrayList<>();
        for (var turn : raw) {
            if (turn.type() != TurnType.TOKEN_USAGE) {
                result.add(RecordTokenCounter.withoutEstimate(turn));
                continue;
            }
            if (result.isEmpty()) continue;
            int index = result.size() - 1;
            if (turn.payload().get("throughTurn") instanceof Number through)
                for (int i = result.size() - 1; i >= 0; i--)
                    if (result.get(i).turnNumber() == through.intValue()) {
                        index = i;
                        break;
                    }
            var migrated = decode(List.of(turn.payload()));
            for (var usage : migrated) result.set(index, add(result.get(index), usage));
        }
        return List.copyOf(result);
    }
}
