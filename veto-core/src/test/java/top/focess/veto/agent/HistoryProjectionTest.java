package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HistoryProjectionTest {
    @Test
    void changedConfigurationRestoresEffectiveContentWithProvenance() {
        var old =
                List.of(
                        TurnRecord.agentInit(1, "leader", "old", "test", "test"),
                        TurnRecord.userPrompt(2, "keep this"));
        var additions = HistoryProjection.reinitialize(old, 2, "standalone", "new", "test", "test");
        assertEquals(TurnType.REWIND, additions.get(0).type());
        assertEquals("new", additions.get(1).payload().get("system_prompt"));
        assertEquals(2, additions.get(2).payload().get("restored_from_turn"));
        var all = new ArrayList<>(old);
        all.addAll(additions);
        assertTrue(
                HistoryProjection.reinitialize(all, 5, "standalone", "new", "test", "test")
                        .isEmpty());
        assertEquals(
                1,
                HistoryProjection.effective(all).stream()
                        .filter(t -> t.type() == TurnType.AGENT_INIT)
                        .count());
    }

    @Test
    void laterRewindCoversOldRewindAndInitializationsWithoutResurrectingHistory() {
        var log =
                List.of(
                        TurnRecord.agentInit(1, "standalone", "old", "test", "test"),
                        TurnRecord.userPrompt(2, "old task"),
                        TurnRecord.rewind(3, 0),
                        TurnRecord.agentInit(4, "leader", "leader", "test", "test"),
                        TurnRecord.userPrompt(5, "delegation"),
                        TurnRecord.rewind(6, 0),
                        TurnRecord.agentInit(7, "standalone", "new", "test", "test"));
        var projected = HistoryProjection.replay(log);
        assertEquals(
                List.of(6, 7),
                HistoryProjection.effective(log).stream().map(TurnRecord::turnNumber).toList());
        assertEquals(6, projected.get(2).removedBy());
        assertEquals(3, projected.get(0).removedBy());
        assertEquals(3, projected.get(5).removedCount());
    }

    @Test
    void legacyMessageIndexRetainsMergedThoughtAndCallButDropsFollowingMetadata() {
        var log =
                List.of(
                        TurnRecord.userPrompt(1, "task"),
                        new TurnRecord(
                                2,
                                TurnType.ASSISTANT_THOUGHT,
                                Map.of("response", "thinking"),
                                null),
                        new TurnRecord(3, TurnType.TOOL_CALL, Map.of("call_id", "c"), null),
                        TurnRecord.agentInit(4, "leader", "later", "test", "test"),
                        new TurnRecord(5, TurnType.REWIND, Map.of("from_index", 2), null));
        assertEquals(
                List.of(1, 2, 3, 5),
                HistoryProjection.effective(log).stream().map(TurnRecord::turnNumber).toList());
    }
}
