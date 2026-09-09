package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import top.focess.veto.session.SessionRecord;

class RecordTokenCounterTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void countsOnlyTheContentAndMarksEstimates() {
        TurnRecord turn =
                new TurnRecord(
                        1,
                        TurnType.TOOL_RESPONSE,
                        Map.of(
                                "content",
                                "abcdef",
                                "call_id",
                                "not part of content",
                                "success",
                                true),
                        null);
        TurnRecord measured = RecordTokenCounter.annotate(turn, mapper, 1.5);
        assertEquals(Long.valueOf(3), (Object) RecordTokenCounter.count(measured.payload()));
        assertEquals("estimated", measured.payload().get("tokenCountSource"));
        assertEquals(turn.payload().get("content"), measured.payload().get("content"));
        assertEquals(
                Long.valueOf(0),
                (Object)
                        RecordTokenCounter.count(
                                RecordTokenCounter.annotate(TurnRecord.userPrompt(2, ""), mapper, 1)
                                        .payload()));
    }

    @Test
    void retainsUnknownLegacyValuesAndDoesNotRecountRestoredRecords() {
        TurnRecord restored =
                new TurnRecord(
                        1,
                        TurnType.USER_PROMPT,
                        Map.of("content", "hello", "restored_from_turn", 5),
                        null);
        assertNull(
                RecordTokenCounter.count(
                        RecordTokenCounter.annotate(restored, mapper, 1).payload()));
        SessionRecord legacy =
                new SessionRecord(
                        "agent",
                        1,
                        "USER_PROMPT",
                        Map.of("content", "hello"),
                        Instant.EPOCH,
                        true,
                        0,
                        0);
        assertNull(legacy.tokenCount());
        assertNull(legacy.tokenCountSource());
    }
}
