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
        List<Invocation> invocations = new ArrayList<>();
        Map<InvocationKey, Deque<Invocation>> awaiting = new LinkedHashMap<>();
        int syntheticResponses = 0;
        int orphanResponses = 0;
        int malformedCalls = 0;

        for (SessionRecord record : records) {
            if ("TOOL_CALL".equals(record.type())) {
                String callId = nonBlankString(record.payload().get("call_id"));
                String toolName = nonBlankString(record.payload().get("tool_name"));
                if (callId == null || toolName == null) {
                    malformedCalls++;
                    continue;
                }
                Invocation invocation = new Invocation(toolName, record);
                invocations.add(invocation);
                awaiting.computeIfAbsent(
                                new InvocationKey(record.agentId(), callId),
                                ignored -> new ArrayDeque<>())
                        .addLast(invocation);
            } else if ("TOOL_RESPONSE".equals(record.type())) {
                String callId = nonBlankString(record.payload().get("call_id"));
                if (callId == null) {
                    syntheticResponses++;
                    continue;
                }
                Deque<Invocation> matches =
                        awaiting.get(new InvocationKey(record.agentId(), callId));
                Invocation invocation = matches == null ? null : matches.pollFirst();
                if (invocation == null) {
                    orphanResponses++;
                } else {
                    invocation.complete(record);
                }
            }
        }

        Map<String, MutableUsage> byTool = new LinkedHashMap<>();
        int activeCalls = 0;
        int successfulCalls = 0;
        int failedCalls = 0;
        int pendingCalls = 0;
        for (Invocation invocation : invocations) {
            MutableUsage usage = byTool.computeIfAbsent(invocation.toolName, MutableUsage::new);
            usage.accept(invocation);
            if (invocation.call.active()) {
                activeCalls++;
            }
            if (invocation.response == null) {
                pendingCalls++;
            } else if (isSuccess(invocation.response)) {
                successfulCalls++;
            } else {
                failedCalls++;
            }
        }

        List<ToolUsage> tools =
                byTool.values().stream()
                        .map(MutableUsage::snapshot)
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

    private static boolean isSuccess(@NonNull SessionRecord response) {
        Object success = response.payload().get("success");
        if (success instanceof Boolean value) {
            return value;
        }
        return "success"
                .equalsIgnoreCase(String.valueOf(response.payload().getOrDefault("status", "")));
    }

    private static String nonBlankString(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static @NonNull String errorCode(@NonNull SessionRecord response) {
        String code = nonBlankString(response.payload().get("errorCode"));
        return code == null ? "UNSPECIFIED" : code;
    }

    private record InvocationKey(@NonNull String agentId, @NonNull String callId) {}

    private static final class Invocation {

        private final @NonNull String toolName;
        private final @NonNull SessionRecord call;
        private SessionRecord response;

        private Invocation(@NonNull String toolName, @NonNull SessionRecord call) {
            this.toolName = toolName;
            this.call = call;
        }

        private void complete(@NonNull SessionRecord response) {
            this.response = response;
        }

        private long durationMillis() {
            SessionRecord completed = response;
            if (completed == null) {
                return 0;
            }
            return Math.max(
                    0, Duration.between(call.timestamp(), completed.timestamp()).toMillis());
        }
    }

    private static final class MutableUsage {

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

        private MutableUsage(@NonNull String toolName) {
            this.toolName = toolName;
        }

        private void accept(@NonNull Invocation invocation) {
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
            if (isSuccess(response)) {
                successfulCalls++;
            } else {
                failedCalls++;
                failuresByCode.merge(errorCode(response), 1, Integer::sum);
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
