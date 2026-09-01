package top.focess.veto.session;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;

/**
 * Tool-call telemetry derived from the durable session records.
 *
 * <p>This is a projection, not a second source of truth. It intentionally contains no argument or
 * result content: those values remain available only in the underlying records view.
 */
public record ToolUsageSummary(
        int totalCalls,
        int activeCalls,
        int rewoundCalls,
        int completedCalls,
        int successfulCalls,
        int failedCalls,
        int pendingCalls,
        int syntheticResponses,
        int orphanResponses,
        int malformedCalls,
        @NonNull List<@NonNull ToolUsage> tools) {

    /** Per-tool aggregate across the complete append-only trace, including rewound calls. */
    public record ToolUsage(
            @NonNull String toolName,
            int totalCalls,
            int activeCalls,
            int rewoundCalls,
            int successfulCalls,
            int failedCalls,
            int pendingCalls,
            long averageDurationMillis,
            long maxDurationMillis,
            @NonNull Instant lastCalledAt,
            @NonNull Map<@NonNull String, @NonNull Integer> failuresByCode) {}
}
