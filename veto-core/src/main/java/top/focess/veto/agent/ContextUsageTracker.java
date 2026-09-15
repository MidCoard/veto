package top.focess.veto.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import top.focess.veto.llm.core.LlmSystemUsage;
import top.focess.veto.llm.core.VetoRequest;

/** Request-boundary measurements. Context deltas are diagnostics, not per-record counts. */
public final class ContextUsageTracker {
    private VetoRequest previous;
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

    public void reset() {
        previous = null;
    }

    public @NonNull Map<String, Object> measure(
            @NonNull VetoRequest request, LlmSystemUsage.@NonNull Usage usage) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("inputTokens", usage.promptTokens());
        data.put("outputTokens", usage.completionTokens());
        Long cacheRead = usage.cacheReadInputTokens();
        Long cacheCreation = usage.cacheCreationInputTokens();
        if (cacheRead != null) data.put("cacheReadInputTokens", cacheRead);
        if (cacheCreation != null) data.put("cacheCreationInputTokens", cacheCreation);
        data.put("contextMaxTokens", request.options().contextWindowOrDefault());
        data.put("model", request.modelName());
        data.put("provider", request.providerType().name());
        data.put("messageCount", request.messages().size());
        VetoRequest before = previous;
        int previousMessageCount = before == null ? 0 : before.messages().size();
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
        data.put("baselineReset", !comparable);
        if (comparable) {
            data.put("contextDeltaTokens", usage.promptTokens() - previousTokens);
            var appended =
                    request.messages().subList(previousMessageCount, request.messages().size());
            if (!appended.isEmpty()) {
                long retainedOutput =
                        appended.stream().anyMatch(message -> message.role().equals("assistant"))
                                ? previousOutputTokens
                                : 0;
                data.put(
                        "inputDeltaTokens", usage.promptTokens() - previousTokens - retainedOutput);
                data.put("inputDeltaSource", "request_difference");
                data.put("subtractedOutputTokens", retainedOutput);
            }
            data.put("fromMessageIndex", previousMessageCount);
            data.put("appendedMessages", request.messages().size() - previousMessageCount);
        }
        previous = request;
        previousTokens = usage.promptTokens();
        previousOutputTokens = usage.completionTokens();
        return data;
    }
}
