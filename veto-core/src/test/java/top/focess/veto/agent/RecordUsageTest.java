package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.*;

class RecordUsageTest {
    @Test
    void separatesUsageFromContentAndPreservesItAcrossCopies() throws Exception {
        var raw = TurnRecord.userPrompt(1, "hello");
        var usage =
                new UsageMeasurement(
                        "call", 19436, 67, 19291L, null, 1000000, "minimax", "ANTHROPIC", null);
        var turn = RecordUsage.add(raw, usage);
        assertEquals(raw.payload(), turn.payload());
        assertEquals(List.of(usage), turn.llmUsage());
        assertEquals(turn.llmUsage(), turn.withTurnNumber(2).llmUsage());
        assertEquals(turn.llmUsage(), RecordTokenCounter.unmeasured(turn).llmUsage());
        var json = new ObjectMapper().findAndRegisterModules().valueToTree(turn);
        assertTrue(json.has("llmUsage"));
        assertFalse(json.path("payload").has("llmUsage"));
        assertFalse(json.toString().contains("baselineReset"));
    }

    @Test
    void migratesLegacyPayloadWithoutAddingHistoryEvents() {
        var legacy =
                new TurnRecord(
                        1,
                        TurnType.USER_PROMPT,
                        Map.of(
                                "content",
                                "hello",
                                "llmUsage",
                                List.of(
                                        Map.of(
                                                "inputTokens",
                                                19436,
                                                "outputTokens",
                                                67,
                                                "baselineReset",
                                                true)),
                                "usageCheckpoint",
                                Map.of("inputTokens", 19291)),
                        null);
        assertEquals(Map.of("content", "hello"), legacy.payload());
        assertEquals(19436, legacy.llmUsage().getFirst().inputTokens());
        assertEquals(1, RecordUsage.contentRecords(List.of(legacy)).size());
    }
}
