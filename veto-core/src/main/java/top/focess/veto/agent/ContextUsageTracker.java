package top.focess.veto.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import top.focess.veto.llm.core.LlmSystemUsage;
import top.focess.veto.llm.core.VetoRequest;

/** Request-boundary measurements. Only unchanged prefixes have an attributable token delta. */
public final class ContextUsageTracker {
    private VetoRequest previous;
    private long previousTokens;
    private int previousThroughTurn = -1;

    public void reset() {
        previous = null;
        previousThroughTurn = -1;
    }

    public @NonNull Map<String, Object> measure(
            @NonNull VetoRequest request, LlmSystemUsage.@NonNull Usage usage) {
        return measure(request, usage, -1);
    }

    public @NonNull Map<String, Object> measure(
            @NonNull VetoRequest request, LlmSystemUsage.@NonNull Usage usage, int throughTurn) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("inputTokens", usage.promptTokens());
        data.put("outputTokens", usage.completionTokens());
        data.put("contextMaxTokens", request.options().contextWindowOrDefault());
        data.put("model", request.modelName());
        data.put("provider", request.providerType().name());
        data.put("messageCount", request.messages().size());
        VetoRequest before = previous;
        boolean comparable =
                before != null
                        && before.providerType() == request.providerType()
                        && before.modelName().equals(request.modelName())
                        && Objects.equals(before.baseUrl(), request.baseUrl())
                        && before.systemPrompt().equals(request.systemPrompt())
                        && before.tools().equals(request.tools())
                        && Objects.equals(before.responseSchema(), request.responseSchema())
                        && before.messages().size() <= request.messages().size()
                        && request.messages()
                                .subList(0, before.messages().size())
                                .equals(before.messages());
        data.put("baselineReset", !comparable);
        if (comparable && before != null) {
            data.put("contextDeltaTokens", usage.promptTokens() - previousTokens);
            data.put("fromMessageIndex", before.messages().size());
            data.put("appendedMessages", request.messages().size() - before.messages().size());
            if (previousThroughTurn >= 0
                    && throughTurn > previousThroughTurn
                    && request.messages().size() > before.messages().size()
                    && usage.promptTokens() >= previousTokens) {
                data.put("recordDelta", true);
                data.put("fromRecordTurn", previousThroughTurn + 1);
            }
        }
        previous = request;
        previousTokens = usage.promptTokens();
        previousThroughTurn = throughTurn;
        return data;
    }
}
