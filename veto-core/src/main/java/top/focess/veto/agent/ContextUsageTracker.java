package top.focess.veto.agent;

import org.jspecify.annotations.NonNull;

import top.focess.veto.llm.core.LlmSystemUsage;
import top.focess.veto.llm.core.VetoRequest;

import java.util.Objects;

/** Request-boundary measurements. Context deltas are diagnostics, not per-record counts. */
public final class ContextUsageTracker {
    private VetoRequest previous;
    private UsageCheckpoint restored;
    private long previousTokens;
    private long previousOutputTokens;

    public record Baseline(VetoRequest request, long inputTokens, long outputTokens) {}

    public @NonNull Baseline baseline() {
        return new Baseline(previous, previousTokens, previousOutputTokens);
    }

    public void accept(@NonNull Baseline input, @NonNull Baseline output) {
        previous = input.request();
        previousTokens = input.inputTokens();
        previousOutputTokens = output.outputTokens();
    }

    public UsageCheckpoint checkpoint() {
        var request = previous;
        return request == null
                ? restored
                : UsageCheckpoint.capture(request, previousTokens, previousOutputTokens);
    }

    public void restore(@NonNull UsageCheckpoint checkpoint) {
        previous = null;
        restored = checkpoint;
        previousTokens = checkpoint.inputTokens();
        previousOutputTokens = checkpoint.outputTokens();
    }

    public void reset() {
        restored = null;
        previous = null;
    }

    public @NonNull UsageMeasurement measure(
            @NonNull VetoRequest request, LlmSystemUsage.@NonNull Usage usage) {
        // Context percentage uses this request's inputTokens / contextMaxTokens.
        // It excludes the latest outputTokens: these are only reflected in context if replayed
        // in a later measured request. Keep this boundary in sync with UI occupancy calculations
        // if the context accounting semantics change.
        VetoRequest before = previous;
        var saved = restored;
        int previousMessageCount =
                before != null
                        ? before.messages().size()
                        : saved == null ? 0 : saved.messageHashes().size();
        boolean comparable =
                before != null
                        && before.providerType() == request.providerType()
                        && before.modelName().equals(request.modelName())
                        && Objects.equals(before.baseUrl(), request.baseUrl())
                        && before.systemPrompt().equals(request.systemPrompt())
                        && before.tools().equals(request.tools())
                        && Objects.equals(before.responseSchema(), request.responseSchema())
                        && previousMessageCount <= request.messages().size()
                        && request.messages()
                                .subList(0, previousMessageCount)
                                .equals(before.messages());
        if (before == null && saved != null)
            comparable = saved.precedes(UsageCheckpoint.capture(request, 0, 0));
        Long delta = comparable ? usage.promptTokens() - previousTokens : null;
        var appended =
                request.messages()
                        .subList(
                                comparable ? previousMessageCount : request.messages().size(),
                                request.messages().size());
        Long retainedOutput =
                !appended.isEmpty()
                        ? (appended.stream().anyMatch(message -> message.role().equals("assistant"))
                                ? previousOutputTokens
                                : 0L)
                        : null;
        UsageMeasurement data =
                new UsageMeasurement(
                        usage.promptTokens(),
                        usage.completionTokens(),
                        usage.cacheReadInputTokens(),
                        usage.cacheCreationInputTokens(),
                        request.options().contextWindowOrDefault(),
                        request.modelName(),
                        request.providerType().name(),
                        request.messages().size(),
                        !comparable,
                        delta,
                        delta != null && retainedOutput != null ? delta - retainedOutput : null,
                        retainedOutput == null ? null : "request_difference",
                        retainedOutput,
                        comparable ? previousMessageCount : null,
                        comparable ? appended.size() : null,
                        null,
                        null,
                        null,
                        null);
        restored = null;
        previous = request;
        previousTokens = usage.promptTokens();
        previousOutputTokens = usage.completionTokens();
        return data;
    }
}
