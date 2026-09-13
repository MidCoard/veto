package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecordUsageTest {
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
        Map<String, Object> measurement =
                Map.of(
                        "recordDelta",
                        true,
                        "contextDeltaTokens",
                        12,
                        "inputTokens",
                        100,
                        "outputTokens",
                        19);
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
                RecordUsage.add(
                        TurnRecord.userPrompt(2, "hello"),
                        Map.of("recordDelta", true, "contextDeltaTokens", 0));
        assertEquals(Long.valueOf(0), RecordTokenCounter.count(zero.payload()));
    }

    @Test
    void enrichesTheSameRecordWithoutCreatingATurn() {
        TurnRecord original = TurnRecord.userPrompt(7, "hello");
        TurnRecord updated =
                RecordUsage.add(original, Map.of("inputTokens", 100, "outputTokens", 5));
        assertEquals(7, updated.turnNumber());
        assertEquals(TurnType.USER_PROMPT, updated.type());
        assertEquals(original.timestamp(), updated.timestamp());
        assertEquals("hello", updated.payload().get("content"));
        assertEquals(
                List.of(Map.of("inputTokens", 100, "outputTokens", 5)),
                updated.payload().get("llmUsage"));
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
