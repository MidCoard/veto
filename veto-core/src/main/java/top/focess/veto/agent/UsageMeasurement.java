package top.focess.veto.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Immutable accounting for one provider request; request identity is independent of its display
 * anchor.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UsageMeasurement(
        long inputTokens,
        long outputTokens,
        @Nullable Long cacheReadInputTokens,
        @Nullable Long cacheCreationInputTokens,
        long contextMaxTokens,
        @NonNull String model,
        @NonNull String provider,
        int messageCount,
        boolean baselineReset,
        @Nullable Long contextDeltaTokens,
        @Nullable Long inputDeltaTokens,
        @Nullable String inputDeltaSource,
        @Nullable Long subtractedOutputTokens,
        @Nullable Integer fromMessageIndex,
        @Nullable Integer appendedMessages,
        @Nullable Integer throughTurn,
        @Nullable String modelCallId,
        @Nullable Boolean affectsContext,
        @Nullable String purpose) {
    public @NonNull UsageMeasurement forRequest(int turn, @NonNull String callId) {
        return copy(turn, callId, null, null);
    }

    public @NonNull UsageMeasurement forCompaction(int turn) {
        return copy(turn, null, false, "compaction");
    }

    private @NonNull UsageMeasurement copy(
            @Nullable Integer turn,
            @Nullable String callId,
            @Nullable Boolean affects,
            @Nullable String reason) {
        return new UsageMeasurement(
                inputTokens,
                outputTokens,
                cacheReadInputTokens,
                cacheCreationInputTokens,
                contextMaxTokens,
                model,
                provider,
                messageCount,
                baselineReset,
                contextDeltaTokens,
                inputDeltaTokens,
                inputDeltaSource,
                subtractedOutputTokens,
                fromMessageIndex,
                appendedMessages,
                turn,
                callId,
                affects,
                reason);
    }
}
