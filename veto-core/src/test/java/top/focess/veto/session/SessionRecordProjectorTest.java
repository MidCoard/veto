package top.focess.veto.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;

class SessionRecordProjectorTest {

    @Test
    void retainsFullTraceAndAnnotatesRecordsSupersededByRewind() {
        List<SessionRecord> projected =
                SessionRecordProjector.project(
                        List.of(
                                record(
                                        1,
                                        "AGENT_INIT",
                                        Map.of(
                                                "role", "standalone",
                                                "system_prompt", "system")),
                                record(2, "USER_PROMPT", Map.of("content", "first")),
                                record(3, "ASSISTANT_THOUGHT", Map.of("response", "thinking")),
                                record(4, "TOOL_CALL", Map.of("call_id", "c1")),
                                record(5, "TOOL_RESPONSE", Map.of("call_id", "c1")),
                                record(6, "USER_PROMPT", Map.of("content", "discard me")),
                                record(7, "REWIND", Map.of("from_index", 1)),
                                record(8, "COMPACTION_SUMMARY", Map.of("content", "summary"))));

        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8), turns(projected));
        assertEquals(List.of(3, 4, 5, 6), inactiveTurns(projected));
        for (SessionRecord record : projected.subList(2, 6)) {
            assertEquals(7, record.rewoundByTurnNumber());
        }
        SessionRecord rewind = projected.get(6);
        assertEquals(4, rewind.rewoundRecords());
        assertTrue(projected.stream().anyMatch(record -> "AGENT_INIT".equals(record.type())));
    }

    @Test
    void rewindWithInjectedContentCanItselfBeDroppedByALaterRewind() {
        List<SessionRecord> projected =
                SessionRecordProjector.project(
                        List.of(
                                record(1, "USER_PROMPT", Map.of("content", "old")),
                                record(2, "REWIND", Map.of("from_index", 0, "content", "recalled")),
                                record(3, "ASSISTANT_RESPONSE", Map.of("content", "answer")),
                                record(4, "REWIND", Map.of("from_index", 0))));

        assertEquals(List.of(1, 2, 3, 4), turns(projected));
        assertEquals(List.of(1, 2, 3), inactiveTurns(projected));
        assertEquals(2, projected.get(3).rewoundRecords());
        assertFalse(projected.get(1).active());
        assertEquals(4, projected.get(1).rewoundByTurnNumber());
    }

    @Test
    void danglingThoughtIsRemovedWhenRewindClearsThePendingAssistantMessage() {
        List<SessionRecord> projected =
                SessionRecordProjector.project(
                        List.of(
                                record(1, "USER_PROMPT", Map.of("content", "task")),
                                record(2, "ASSISTANT_THOUGHT", Map.of("response", "pending")),
                                record(3, "REWIND", Map.of("from_index", 1))));

        assertEquals(List.of(1, 2, 3), turns(projected));
        assertEquals(List.of(2), inactiveTurns(projected));
        assertEquals(3, projected.get(1).rewoundByTurnNumber());
        assertEquals(1, projected.get(2).rewoundRecords());
    }

    private static @NonNull SessionRecord record(
            int turn, @NonNull String type, @NonNull Map<String, Object> payload) {
        return new SessionRecord(
                "agent", turn, type, payload, Instant.ofEpochSecond(turn), true, 0, 0);
    }

    private static @NonNull List<Integer> turns(@NonNull List<SessionRecord> records) {
        return records.stream().map(SessionRecord::turnNumber).toList();
    }

    private static @NonNull List<Integer> inactiveTurns(@NonNull List<SessionRecord> records) {
        return records.stream()
                .filter(record -> !record.active())
                .map(SessionRecord::turnNumber)
                .toList();
    }
}
