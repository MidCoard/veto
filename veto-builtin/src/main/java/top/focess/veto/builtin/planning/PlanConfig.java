package top.focess.veto.builtin.planning;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.contract.JsonValue;

/** Builtin-owned ceiling for executed plan steps, including repeated branch steps. */
public record PlanConfig(int maxSteps) {
    public PlanConfig {
        if (maxSteps < 1) throw new IllegalArgumentException("plan-max-steps must be positive");
    }

    public static @NonNull PlanConfig defaults() {
        return new PlanConfig(1000);
    }

    public static @NonNull PlanConfig from(JsonValue.@NonNull ObjectValue configuration) {
        var value = configuration.values().get("plan-max-steps");
        if (value == null) return defaults();
        if (value instanceof JsonValue.StringValue text)
            return new PlanConfig(Integer.parseInt(text.value()));
        throw new IllegalArgumentException("plan-max-steps must be an integer string");
    }
}
