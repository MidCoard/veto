package top.focess.veto.agent;

import com.fasterxml.jackson.databind.node.NullNode;

import org.jspecify.annotations.NonNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads only actual measurements; historical estimates are not measurements. */
public final class RecordTokenCounter {
    private RecordTokenCounter() {}

    public static @NonNull TurnRecord unmeasured(@NonNull TurnRecord turn) {
        Map<String, Object> payload = new LinkedHashMap<>(turn.payload());
        payload.remove("usedTokens");
        payload.remove("tokenCount");
        payload.remove("tokenCountSource");
        payload.remove("tokenDeltaFromTurn");
        // Keep an explicit JSON null without violating the payload's non-null value contract.
        if (turn.type() == TurnType.ASSISTANT_THOUGHT)
            payload.put("usedTokens", NullNode.getInstance());
        return new TurnRecord(
                turn.turnNumber(), turn.type(), payload, turn.timestamp(), turn.llmUsage());
    }

    public static @NonNull TurnRecord withoutEstimate(@NonNull TurnRecord turn) {
        return turn.type() == TurnType.ASSISTANT_THOUGHT
                        || "estimated".equals(turn.payload().get("tokenCountSource"))
                        || hasRequestDelta(turn.payload())
                ? unmeasured(turn)
                : turn;
    }

    public static Long count(@NonNull Map<String, Object> payload) {
        if (hasRequestDelta(payload)) return null;
        if (!"measured".equals(payload.get("tokenCountSource"))) return null;
        Object value = payload.get("usedTokens");
        return value instanceof Number number && number.longValue() >= 0
                ? number.longValue()
                : null;
    }

    private static boolean hasRequestDelta(@NonNull Map<String, Object> payload) {
        if (payload.containsKey("tokenDeltaFromTurn")) return true;
        return payload.get("llmUsage") instanceof List<?> measurements
                && measurements.stream()
                        .anyMatch(
                                value ->
                                        value instanceof Map<?, ?> usage
                                                && Boolean.TRUE.equals(usage.get("recordDelta")));
    }
}
