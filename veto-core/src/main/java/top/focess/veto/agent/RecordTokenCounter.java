package top.focess.veto.agent;

import com.fasterxml.jackson.databind.node.NullNode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.NonNull;

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
        return new TurnRecord(turn.turnNumber(), turn.type(), payload, turn.timestamp());
    }

    public static @NonNull TurnRecord withoutEstimate(@NonNull TurnRecord turn) {
        return turn.type() == TurnType.ASSISTANT_THOUGHT
                        || "estimated".equals(turn.payload().get("tokenCountSource"))
                ? unmeasured(turn)
                : turn;
    }

    public static Long count(@NonNull Map<String, Object> payload) {
        if (!"measured".equals(payload.get("tokenCountSource"))) return null;
        Object value = payload.get("usedTokens");
        return value instanceof Number number && number.longValue() >= 0
                ? number.longValue()
                : null;
    }
}
