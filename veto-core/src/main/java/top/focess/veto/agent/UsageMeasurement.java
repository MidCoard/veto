package top.focess.veto.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.*;
import top.focess.veto.api.llm.LlmSystemUsage;
import top.focess.veto.api.llm.VetoRequest;

/** Raw provider measurements. Display differences are derived from the ordered request history. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record UsageMeasurement(
        String modelCallId,
        long inputTokens,
        long outputTokens,
        Long cacheReadInputTokens,
        Long cacheCreationInputTokens,
        long contextMaxTokens,
        String model,
        String provider,
        String purpose) {
    public static @NonNull UsageMeasurement measured(
            @NonNull VetoRequest request, LlmSystemUsage.@NonNull Usage usage) {
        return new UsageMeasurement(
                null,
                usage.promptTokens(),
                usage.completionTokens(),
                usage.cacheReadInputTokens(),
                usage.cacheCreationInputTokens(),
                request.options().contextWindowOrDefault(),
                request.modelName(),
                request.providerType().name(),
                null);
    }

    public @NonNull UsageMeasurement forRequest(@NonNull String callId) {
        return new UsageMeasurement(
                callId,
                inputTokens,
                outputTokens,
                cacheReadInputTokens,
                cacheCreationInputTokens,
                contextMaxTokens,
                model,
                provider,
                null);
    }

    public @NonNull UsageMeasurement forCompaction() {
        return new UsageMeasurement(
                java.util.UUID.randomUUID().toString(),
                inputTokens,
                outputTokens,
                cacheReadInputTokens,
                cacheCreationInputTokens,
                contextMaxTokens,
                model,
                provider,
                "compaction");
    }
}
