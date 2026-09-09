package top.focess.veto.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;

/** Usage is metadata on an existing content record, never an additional conversation event. */
public final class RecordUsage {
    private RecordUsage() {}

    public static @NonNull TurnRecord add(
            @NonNull TurnRecord turn, @NonNull Map<String, Object> measurement) {
        Map<String, Object> payload = new LinkedHashMap<>(turn.payload());
        List<Object> usage = new ArrayList<>();
        if (payload.get("llmUsage") instanceof List<?> previous) {
            for (Object value : previous) if (value != null) usage.add(value);
        }
        usage.add(measurement);
        payload.put("llmUsage", usage);
        if (Boolean.TRUE.equals(measurement.get("recordDelta"))
                && measurement.get("contextDeltaTokens") instanceof Number delta
                && delta.longValue() >= 0) {
            payload.put("usedTokens", delta.longValue());
            payload.put("tokenCountSource", "measured");
            if (measurement.get("fromRecordTurn") instanceof Number fromTurn)
                payload.put("tokenDeltaFromTurn", fromTurn);
        }
        return new TurnRecord(turn.turnNumber(), turn.type(), payload, turn.timestamp());
    }

    /**
     * Read compatibility for the short-lived standalone-usage format; original rows stay intact.
     */
    public static @NonNull List<TurnRecord> contentRecords(@NonNull List<TurnRecord> raw) {
        List<TurnRecord> result = new ArrayList<>();
        for (TurnRecord turn : raw) {
            if (turn.type() != TurnType.TOKEN_USAGE) {
                result.add(RecordTokenCounter.withoutEstimate(turn));
                continue;
            }
            int target = result.size() - 1;
            if (turn.payload().get("throughTurn") instanceof Number through) {
                for (int i = result.size() - 1; i >= 0; i--) {
                    if (result.get(i).turnNumber() == through.intValue()) {
                        target = i;
                        break;
                    }
                }
            }
            if (target >= 0) result.set(target, add(result.get(target), turn.payload()));
        }
        return List.copyOf(result);
    }
}
