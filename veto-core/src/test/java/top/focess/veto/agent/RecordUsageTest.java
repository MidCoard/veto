package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecordUsageTest {
    private static @org.jspecify.annotations.NonNull UsageMeasurement measurement(
            long input, long output, long delta) {
        return new UsageMeasurement(
                input, output, null, null, 1000, "test", "test", 1, false, delta, null, null, null,
                null, null, null, null, null, null);
    }

    @Test
    void thoughtHasExplicitNullThroughAppendUsageAndHistoricalProjection() throws Exception {
        TurnRecord historical =
                new TurnRecord(
                        1,
                        TurnType.ASSISTANT_THOUGHT,
                        Map.of(
                                "content",
                                "thinking",
                                "usedTokens",
                                19,
                                "tokenCountSource",
                                "measured",
                                "model_call_id",
                                "call-1"),
                        null);
        var measurement = measurement(100, 19, 12);
        TurnRecord updated = RecordUsage.add(historical, measurement);
        for (TurnRecord thought :
                List.of(
                        RecordTokenCounter.unmeasured(historical),
                        updated,
                        RecordUsage.contentRecords(List.of(historical)).getFirst())) {
            assertTrue(thought.payload().containsKey("usedTokens"));
            String json = new ObjectMapper().writeValueAsString(thought.payload());
            assertTrue(json.contains("\"usedTokens\":null"), json);
            assertNull(RecordTokenCounter.count(thought.payload()));
            assertEquals("call-1", thought.payload().get("model_call_id"));
            assertEquals(historical.timestamp(), thought.timestamp());
        }
        assertEquals(List.of(measurement), updated.payload().get("llmUsage"));
        TurnRecord zero =
                RecordUsage.add(TurnRecord.userPrompt(2, "hello"), measurement(100, 0, 0));
        assertNull(RecordTokenCounter.count(zero.payload()));
        assertEquals(
                Long.valueOf(0),
                RecordTokenCounter.count(Map.of("usedTokens", 0, "tokenCountSource", "measured")));
    }

    @Test
    void rejectsHistoricalRequestDeltasWithoutChangingRawAuditOrCallUsage() {
        var measurement =
                Map.<String, Object>of(
                        "inputTokens",
                        125,
                        "outputTokens",
                        8,
                        "recordDelta",
                        true,
                        "contextDeltaTokens",
                        25);
        for (Map<String, Object> marker :
                List.of(
                        Map.<String, Object>of("tokenDeltaFromTurn", 3),
                        Map.<String, Object>of("llmUsage", List.of(measurement)))) {
            var payload = new LinkedHashMap<>(marker);
            payload.put("usedTokens", 25);
            payload.put("tokenCountSource", "measured");
            var raw = new TurnRecord(4, TurnType.USER_PROMPT, payload, null);
            var projected = RecordUsage.contentRecords(List.of(raw)).getFirst();
            assertNull(RecordTokenCounter.count(raw.payload()));
            assertFalse(projected.payload().containsKey("usedTokens"));
            assertFalse(projected.payload().containsKey("tokenCountSource"));
            assertEquals(25, raw.payload().get("usedTokens"));
            var current = measurement(125, 8, 25);
            var updated = RecordUsage.add(raw, current);
            assertFalse(updated.payload().containsKey("usedTokens"));
            assertTrue(
                    updated.payload().get("llmUsage") instanceof List<?> calls
                            && calls.contains(current));
        }
    }

    @Test
    void enrichesTheSameRecordWithoutCreatingATurn() {
        TurnRecord original = TurnRecord.userPrompt(7, "hello");
        TurnRecord updated = RecordUsage.add(original, measurement(100, 5, 0));
        assertEquals(7, updated.turnNumber());
        assertEquals(TurnType.USER_PROMPT, updated.type());
        assertEquals(original.timestamp(), updated.timestamp());
        assertEquals("hello", updated.payload().get("content"));
        assertEquals(List.of(measurement(100, 5, 0)), updated.payload().get("llmUsage"));
    }

    @Test
    void foldsLegacyUsageIntoItsTargetWithoutLosingRetries() {
        TurnRecord input = TurnRecord.userPrompt(1, "hello");
        Map<String, Object> usage = Map.of("throughTurn", 1, "inputTokens", 100, "outputTokens", 5);
        List<TurnRecord> normalized =
                RecordUsage.contentRecords(
                        List.of(
                                input,
                                new TurnRecord(2, TurnType.TOKEN_USAGE, usage, null),
                                new TurnRecord(3, TurnType.TOKEN_USAGE, usage, null),
                                TurnRecord.assistantResponse(4, "Hi")));
        assertEquals(2, normalized.size());
        assertEquals(List.of(usage, usage), normalized.getFirst().payload().get("llmUsage"));
        assertEquals(4, normalized.getLast().turnNumber());
        assertNull(RecordTokenCounter.count(normalized.getFirst().payload()));
    }
}
