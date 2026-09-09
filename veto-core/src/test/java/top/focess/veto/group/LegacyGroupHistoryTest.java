package top.focess.veto.group;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.session.SessionRecord;

class LegacyGroupHistoryTest {
    private static @NonNull SessionRecord record(
            int n, @NonNull String type, @NonNull Map<String, Object> payload) {
        return new SessionRecord(
                "leader", n, type, payload, Instant.ofEpochSecond(n), false, 20, 0);
    }

    @Test
    void recoversAReportFromInspectionWhenMateRecordsWereNeverSaved() {
        var log =
                List.of(
                        record(
                                1,
                                "TOOL_CALL",
                                Map.of(
                                        "tool_name",
                                        "create_group",
                                        "call_id",
                                        "g",
                                        "args",
                                        Map.of("task", "Read"))),
                        record(2, "TOOL_RESPONSE", Map.of("call_id", "g", "success", true)),
                        record(
                                3,
                                "TOOL_CALL",
                                Map.of(
                                        "tool_name",
                                        "create_node",
                                        "call_id",
                                        "n",
                                        "args",
                                        Map.of("nodeId", "page", "description", "Read page"))),
                        record(4, "TOOL_RESPONSE", Map.of("call_id", "n", "success", true)),
                        record(
                                5,
                                "TOOL_CALL",
                                Map.of("tool_name", "inspect_group", "call_id", "i")),
                        record(
                                6,
                                "TOOL_RESPONSE",
                                Map.of(
                                        "call_id",
                                        "i",
                                        "success",
                                        true,
                                        "content",
                                        "Group state: ACTIVE\nNodes:\n- page [VERIFIED] mate=mate1 skillset=reader\n  report: first line\\nsecond line\nNew Mate messages:\n- (none)")));
        var node = LegacyGroupHistory.read(log).get(0).nodes().get(0);
        assertEquals("COMPLETED", node.state());
        assertEquals("first line\nsecond line", node.report());
    }

    @Test
    void rewoundCallsRemainExecutionEvidenceAndMissingStateIsNotInvented() {
        var log =
                List.of(
                        record(
                                1,
                                "TOOL_CALL",
                                Map.of(
                                        "tool_name",
                                        "create_group",
                                        "call_id",
                                        "g",
                                        "args",
                                        Map.of("task", "Read pages"))),
                        record(2, "TOOL_RESPONSE", Map.of("call_id", "g", "success", true)),
                        record(
                                3,
                                "TOOL_CALL",
                                Map.of(
                                        "tool_name",
                                        "create_node",
                                        "call_id",
                                        "n",
                                        "args",
                                        Map.of(
                                                "nodeId",
                                                "page",
                                                "description",
                                                "Read page",
                                                "skillset",
                                                "reader",
                                                "dependsOn",
                                                List.of()))),
                        record(4, "TOOL_RESPONSE", Map.of("call_id", "n", "success", true)),
                        record(
                                5,
                                "TOOL_CALL",
                                Map.of("tool_name", "inspect_group", "call_id", "i")),
                        record(
                                6,
                                "TOOL_RESPONSE",
                                Map.of(
                                        "call_id",
                                        "i",
                                        "success",
                                        true,
                                        "content",
                                        "Group state: ACTIVE\nNodes:\n- page [RUNNING] mate=mate1 skillset=reader")),
                        new SessionRecord(
                                "mate1",
                                1,
                                "USER_PROMPT",
                                Map.of("content", "Read page"),
                                Instant.ofEpochSecond(6),
                                true,
                                0,
                                0),
                        new SessionRecord(
                                "mate1",
                                2,
                                "ASSISTANT_RESPONSE",
                                Map.of("content", "Actual page report"),
                                Instant.ofEpochSecond(7),
                                true,
                                0,
                                0));
        var view = LegacyGroupHistory.read(log).get(0);
        assertTrue(view.historical());
        assertFalse(view.live());
        assertEquals("REPORTED", view.nodes().get(0).state());
        assertEquals("mate1", view.nodes().get(0).mateId());
        assertEquals("Actual page report", view.nodes().get(0).report());
    }
}
