package top.focess.veto.builtin.web;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.agent.AgentProfile;
import top.focess.veto.api.plugin.agent.IsolatedAgent;
import top.focess.veto.api.plugin.contract.JsonValue;

/** Raw web-reader settings, validated when the isolated reader spec is built. */
public record ReaderConfig(@NonNull Map<@NonNull String, @NonNull JsonValue> values) {
    public ReaderConfig {
        values = Map.copyOf(values);
    }

    /** Returns an empty configuration that uses the builtin defaults. */
    public static @NonNull ReaderConfig defaults() {
        return new ReaderConfig(Map.of());
    }

    private @NonNull String text(@NonNull String key, @NonNull String fallback) {
        var v = values.get(key);
        return v instanceof JsonValue.StringValue s ? s.value() : fallback;
    }

    private int number(@NonNull String key, int fallback) {
        var value = values.get(key);
        try {
            if (value instanceof JsonValue.NumberValue number)
                return number.value().intValueExact();
            return Integer.parseInt(text(key, Integer.toString(fallback)));
        } catch (ArithmeticException | NumberFormatException error) {
            throw new IllegalArgumentException("Invalid integer reader setting: " + key, error);
        }
    }

    /** Builds the isolated reader agent spec, validating tier and limit settings. */
    public IsolatedAgent.@NonNull Spec spec() {
        String tier = text("reader-model-tier", "LOW");
        List<String> tiers =
                switch (tier) {
                    case "LOW" -> List.of("LOW", "MID", "TOP");
                    case "MID" -> List.of("MID", "TOP");
                    case "TOP" -> List.of("TOP");
                    default -> throw new IllegalArgumentException("Invalid reader tier");
                };
        int calls = number("reader-max-rounds", 12),
                input = number("reader-max-input-tokens", 32000),
                output = number("reader-max-output-tokens", 4096);
        if (calls < 3 || input < 16000 || output < 256)
            throw new IllegalArgumentException("Invalid reader limits");
        return new IsolatedAgent.Spec(
                "web_fetch · 网页阅读",
                prompt("reader-persona-description"),
                prompt("web-fetch-system-prompt"),
                tiers,
                new IsolatedAgent.Limits(
                        calls,
                        Duration.ofSeconds(number("reader-timeout-seconds", 120)),
                        input,
                        output,
                        2048),
                new IsolatedAgent.Terminal(
                        "finish_read", calls >= 4 ? 2 : 1, prompt("reader-completion")));
    }

    private static AgentProfile.@NonNull Prompt prompt(@NonNull String name) {
        return new AgentProfile.Prompt(name, new JsonValue.ObjectValue(Map.of()));
    }
}
