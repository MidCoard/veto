package top.focess.veto.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.NonNull;

/** Estimates individual content blocks, excluding record metadata and request framing. */
public final class RecordTokenCounter {
    private RecordTokenCounter() {}

    public static @NonNull TurnRecord annotate(
            @NonNull TurnRecord turn, @NonNull ObjectMapper mapper, double factor) {
        // Restored records retain their original measurement, including unknown legacy values.
        if (turn.payload().containsKey("restored_from_turn")) return turn;
        Map<String, Object> payload = new LinkedHashMap<>(turn.payload());
        String[] fields =
                switch (turn.type()) {
                    case AGENT_INIT -> new String[] {"system_prompt"};
                    case USER_PROMPT,
                            ASSISTANT_RESPONSE,
                            TOOL_RESPONSE,
                            COMPACTION_SUMMARY,
                            MONITOR_EVENT ->
                            new String[] {"content"};
                    case USER_INTERRUPT -> new String[] {"feedback"};
                    case ASSISTANT_THOUGHT -> new String[] {"response", "reasoning_content"};
                    case TOOL_CALL -> new String[] {"tool_name", "args"};
                    default -> new String[0];
                };
        if (fields.length == 0) return turn;
        long bytes = 0;
        boolean found = false;
        for (String field : fields) {
            Object value = payload.get(field);
            if (value == null) continue;
            found = true;
            String text = value instanceof String s ? s : mapper.valueToTree(value).toString();
            bytes += text.getBytes(StandardCharsets.UTF_8).length;
        }
        if (!found) return turn;
        double calibrated = Double.isFinite(factor) && factor > 0 ? factor : 1;
        payload.put("usedTokens", (long) Math.ceil(bytes / 3.0 * calibrated));
        payload.put("tokenCountSource", "estimated");
        return new TurnRecord(turn.turnNumber(), turn.type(), payload, turn.timestamp());
    }

    public static Long count(@NonNull Map<String, Object> payload) {
        Object value =
                payload.containsKey("usedTokens")
                        ? payload.get("usedTokens")
                        : payload.get("tokenCount");
        return value instanceof Number number && number.longValue() >= 0
                ? number.longValue()
                : null;
    }
}
