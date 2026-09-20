package top.focess.veto.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jspecify.annotations.*;

import java.util.*;

/** Accounting is record metadata, never model message content. */
public final class RecordUsage {
    private static final @NonNull ObjectMapper JSON = new ObjectMapper();

    private RecordUsage() {}

    public static @NonNull List<UsageMeasurement> decode(@Nullable Object value) {
        if (value == null) return List.of();
        return JSON.convertValue(value, new TypeReference<List<UsageMeasurement>>() {});
    }

    public static @NonNull List<UsageMeasurement> read(@Nullable String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return JSON.readValue(json, new TypeReference<List<UsageMeasurement>>() {});
        } catch (Exception failure) {
            throw new IllegalStateException("Invalid request usage metadata", failure);
        }
    }

    public static @NonNull TurnRecord add(
            @NonNull TurnRecord turn, @NonNull UsageMeasurement usage) {
        turn = RecordTokenCounter.withoutEstimate(turn);
        var values = new ArrayList<>(turn.llmUsage());
        values.add(usage);
        return new TurnRecord(
                turn.turnNumber(), turn.type(), turn.payload(), turn.timestamp(), values);
    }

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
