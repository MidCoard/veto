package top.focess.veto.llm.client;

import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import top.focess.veto.llm.core.VetoRequest;
import top.focess.veto.llm.exceptions.ModelCapabilityException;

/** Reasoning switches for supported model families; unknown models retain compatible defaults. */
final class ModelReasoning {
    private ModelReasoning() {}

    static @NonNull Map<String, Object> anthropic(@NonNull VetoRequest request) {
        String model = request.modelName().toLowerCase(Locale.ROOT);
        if (model.startsWith("minimax-m3")) return Map.of("type", "adaptive");
        if (model.matches("claude-(opus|sonnet)-4-(?:[6-9]|[1-9][0-9]+)(?:-.*)?"))
            return Map.of("type", "adaptive", "display", "summarized");
        if (model.startsWith("claude-") && (model.contains("-4") || model.contains("-3-7"))) {
            int output = request.options().maxTokensOrDefault();
            if (output <= 1024)
                throw new ModelCapabilityException(
                        "Claude thinking requires max output tokens greater than 1024");
            return Map.of("type", "enabled", "budget_tokens", Math.max(1024, output / 2));
        }
        return Map.of();
    }

    static boolean openAi(@NonNull String model) {
        if (model.startsWith("o1-mini")
                || model.startsWith("o1-preview")
                || model.contains("-chat")) return false;
        return model.matches("(?:o[134](?:-.*)?|gpt-5(?:[.-].*)?)");
    }

    static boolean gemini(@NonNull String model) {
        return model.startsWith("gemini-2.5-") || model.startsWith("gemini-3");
    }
}
