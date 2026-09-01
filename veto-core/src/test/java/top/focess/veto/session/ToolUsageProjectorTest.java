package top.focess.veto.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.session.ToolUsageSummary.ToolUsage;

class ToolUsageProjectorTest {

    private static final @NonNull Instant START = Instant.parse("2026-09-02T00:00:00Z");

    @Test
    void derivesUsageByCorrelatingCallsAndResponsesWithoutCopyingContent() {
        List<SessionRecord> records =
                List.of(
                        record(
                                "leader",
                                1,
                                "TOOL_CALL",
                                Map.of("call_id", "c1", "tool_name", "view_file"),
                                0,
                                true),
                        record(
                                "leader",
                                2,
                                "TOOL_RESPONSE",
                                Map.of(
                                        "call_id",
                                        "c1",
                                        "success",
                                        true,
                                        "content",
                                        "secret output is not projected"),
                                25,
                                true),
                        record(
                                "leader",
                                3,
                                "TOOL_CALL",
                                Map.of("call_id", "c2", "tool_name", "grep_search"),
                                30,
                                false),
                        record(
                                "leader",
                                4,
                                "TOOL_RESPONSE",
                                Map.of(
                                        "call_id",
                                        "c2",
                                        "status",
                                        "failure",
                                        "errorCode",
                                        "INVALID_ARGUMENTS"),
                                70,
                                false),
                        record(
                                "mate-1",
                                1,
                                "TOOL_CALL",
                                Map.of("call_id", "c1", "tool_name", "run_task"),
                                80,
                                true),
                        record(
                                "leader",
                                5,
                                "TOOL_RESPONSE",
                                Map.of("content", "synthetic observation", "success", false),
                                90,
                                true),
                        record(
                                "leader",
                                6,
                                "TOOL_RESPONSE",
                                Map.of("call_id", "unknown", "success", false),
                                100,
                                true),
                        record(
                                "leader",
                                7,
                                "TOOL_CALL",
                                Map.of("call_id", "malformed"),
                                110,
                                true));

        ToolUsageSummary summary = ToolUsageProjector.project(records);

        assertEquals(3, summary.totalCalls());
        assertEquals(2, summary.activeCalls());
        assertEquals(1, summary.rewoundCalls());
        assertEquals(2, summary.completedCalls());
        assertEquals(1, summary.successfulCalls());
        assertEquals(1, summary.failedCalls());
        assertEquals(1, summary.pendingCalls());
        assertEquals(1, summary.syntheticResponses());
        assertEquals(1, summary.orphanResponses());
        assertEquals(1, summary.malformedCalls());

        ToolUsage viewFile = usage(summary, "view_file");
        assertEquals(1, viewFile.successfulCalls());
        assertEquals(25, viewFile.averageDurationMillis());
        assertEquals(25, viewFile.maxDurationMillis());

        ToolUsage grepSearch = usage(summary, "grep_search");
        assertEquals(1, grepSearch.rewoundCalls());
        assertEquals(Map.of("INVALID_ARGUMENTS", 1), grepSearch.failuresByCode());

        ToolUsage runTask = usage(summary, "run_task");
        assertEquals(1, runTask.pendingCalls());
        assertEquals(0, runTask.averageDurationMillis());
    }

    private static @NonNull ToolUsage usage(
            @NonNull ToolUsageSummary summary, @NonNull String toolName) {
        return summary.tools().stream()
                .filter(usage -> toolName.equals(usage.toolName()))
                .findFirst()
                .orElseThrow();
    }

    private static @NonNull SessionRecord record(
            @NonNull String agentId,
            int turnNumber,
            @NonNull String type,
            @NonNull Map<String, Object> payload,
            long offsetMillis,
            boolean active) {
        return new SessionRecord(
                agentId,
                turnNumber,
                type,
                payload,
                START.plusMillis(offsetMillis),
                active,
                active ? 0 : 99,
                0);
    }
}
