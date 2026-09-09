package top.focess.veto.agent.loop;

import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Explicit local input budgets, keyed by provider/model; these are not discovered model limits. */
@Component
@ConfigurationProperties(prefix = "veto.context")
public class ContextBudgetConfiguration {
    private @NonNull Map<String, Integer> modelInputTokens = Map.of();

    public @NonNull Map<String, Integer> getModelInputTokens() {
        return modelInputTokens;
    }

    public void setModelInputTokens(@NonNull Map<String, Integer> values) {
        if (values.values().stream().anyMatch(value -> value == null || value <= 0)) {
            throw new IllegalArgumentException("Model input budgets must be positive");
        }
        modelInputTokens = Map.copyOf(values);
    }
}
