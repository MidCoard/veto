package top.focess.veto.session;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import top.focess.veto.session.ToolUsageSummary.ToolUsage;

/** Correlates TOOL_CALL and TOOL_RESPONSE records into a content-free usage projection. */
final class ToolUsageProjector {

    private ToolUsageProjector() {}

    static @NonNull ToolUsageSummary project(@NonNull List<@NonNull SessionRecord> records) {
        List<RecordedToolInvocation> invocations = new ArrayList<>();
        Map<ToolCallIdentity, Deque<RecordedToolInvocation>> awaiting = new LinkedHashMap<>();
        int syntheticResponses = 0;
        int orphanResponses = 0;
        int malformedCalls = 0;

        Map<String, SessionRecord> executions = new LinkedHashMap<>();
        for (SessionRecord record : records) {
            Object origin = record.payload().get("restored_from_turn");
            executions.put(
                    record.agentId()
                            + ":"
                            + (origin instanceof Number number
                                    ? number.intValue()
                                    : record.turnNumber()),
                    record);
        }
        for (SessionRecord record : executions.values()) {
            if ("TOOL_CALL".equals(record.type())) {
                String callId = nonBlankString(record.payload().get("call_id"));
                String toolName = nonBlankString(record.payload().get("tool_name"));
                if (callId == null || toolName == null) {
                    malformedCalls++;
                    continue;
                }
                RecordedToolInvocation invocation = new RecordedToolInvocation(toolName, record);
                invocations.add(invocation);
                awaiting.computeIfAbsent(
                                new ToolCallIdentity(record.agentId(), callId),
                                ignored -> new ArrayDeque<>())
                        .addLast(invocation);
            } else if ("TOOL_RESPONSE".equals(record.type())) {
                String callId = nonBlankString(record.payload().get("call_id"));
                if (callId == null) {
                    syntheticResponses++;
                    continue;
                }
                Deque<RecordedToolInvocation> matches =
                        awaiting.get(new ToolCallIdentity(record.agentId(), callId));
                RecordedToolInvocation invocation = matches == null ? null : matches.pollFirst();
                if (invocation == null) {
                    orphanResponses++;
                } else {
                    invocation.response = record;
                }
            }
        }

        Map<String, ToolUsageAccumulator> byTool = new LinkedHashMap<>();
        int activeCalls = 0;
        int successfulCalls = 0;
        int failedCalls = 0;
        int pendingCalls = 0;
        for (RecordedToolInvocation invocation : invocations) {
            ToolUsageAccumulator usage =
                    byTool.computeIfAbsent(invocation.toolName, ToolUsageAccumulator::new);
            usage.accept(invocation);
            if (invocation.call.active()) {
                activeCalls++;
            }
            if (invocation.response == null) {
                pendingCalls++;
            } else if (invocation.successful()) {
                successfulCalls++;
            } else {
                failedCalls++;
            }
        }

        List<ToolUsage> tools =
                byTool.values().stream()
                        .map(ToolUsageAccumulator::snapshot)
                        .sorted(
                                Comparator.comparingInt(ToolUsage::totalCalls)
                                        .reversed()
                                        .thenComparing(ToolUsage::toolName))
                        .toList();
        int totalCalls = invocations.size();
        return new ToolUsageSummary(
                totalCalls,
                activeCalls,
                totalCalls - activeCalls,
                totalCalls - pendingCalls,
                successfulCalls,
                failedCalls,
                pendingCalls,
                syntheticResponses,
                orphanResponses,
                malformedCalls,
                tools);
    }

    private static String nonBlankString(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    /** Correlates a persisted tool response to the calling agent and tool call. */
    private record ToolCallIdentity(@NonNull String agentId, @NonNull String callId) {}

    /** One call and its optional response, confined to a single usage projection. */
    private static final class RecordedToolInvocation {

        private final @NonNull String toolName;
        private final @NonNull SessionRecord call;
        private SessionRecord response;

        private RecordedToolInvocation(@NonNull String toolName, @NonNull SessionRecord call) {
            this.toolName = toolName;
            this.call = call;
        }

        private long durationMillis() {
            SessionRecord completed = response;
            if (completed == null) {
                return 0;
            }
            return Math.max(
                    0, Duration.between(call.timestamp(), completed.timestamp()).toMillis());
        }

        private boolean successful() {
            SessionRecord completed = response;
            if (completed == null) return false;
            Object success = completed.payload().get("success");
            if (success instanceof Boolean value) {
                return value;
            }
            return "success".equalsIgnoreCase(String.valueOf(completed.payload().get("status")));
        }
    }

    /** Mutable per-tool counters confined to a single usage projection. */
    private static final class ToolUsageAccumulator {

        private final @NonNull String toolName;
        private final @NonNull Map<@NonNull String, @NonNull Integer> failuresByCode =
                new LinkedHashMap<>();
        private int totalCalls;
        private int activeCalls;
        private int successfulCalls;
        private int failedCalls;
        private int pendingCalls;
        private long totalDurationMillis;
        private long maxDurationMillis;
        private Instant lastCalledAt;

        private ToolUsageAccumulator(@NonNull String toolName) {
            this.toolName = toolName;
        }

        private void accept(@NonNull RecordedToolInvocation invocation) {
            totalCalls++;
            if (invocation.call.active()) {
                activeCalls++;
            }
            Instant calledAt = invocation.call.timestamp();
            if (lastCalledAt == null || calledAt.isAfter(lastCalledAt)) {
                lastCalledAt = calledAt;
            }
            SessionRecord response = invocation.response;
            if (response == null) {
                pendingCalls++;
                return;
            }
            long duration = invocation.durationMillis();
            totalDurationMillis += duration;
            maxDurationMillis = Math.max(maxDurationMillis, duration);
            if (invocation.successful()) {
                successfulCalls++;
            } else {
                failedCalls++;
                Object value = response.payload().get("errorCode");
                String code =
                        value instanceof String text && !text.isBlank() ? text : "UNSPECIFIED";
                failuresByCode.merge(code, 1, Integer::sum);
            }
        }

        private @NonNull ToolUsage snapshot() {
            int completed = successfulCalls + failedCalls;
            Instant latest = lastCalledAt;
            if (latest == null) {
                throw new IllegalStateException("A tool usage aggregate must contain a call");
            }
            return new ToolUsage(
                    toolName,
                    totalCalls,
                    activeCalls,
                    totalCalls - activeCalls,
                    successfulCalls,
                    failedCalls,
                    pendingCalls,
                    completed == 0 ? 0 : totalDurationMillis / completed,
                    maxDurationMillis,
                    latest,
                    Map.copyOf(failuresByCode));
        }
    }
}
