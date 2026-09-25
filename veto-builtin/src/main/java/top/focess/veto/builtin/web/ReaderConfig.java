package top.focess.veto.builtin.web;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.checkerframework.framework.qual.TypeUseLocation;
import org.jspecify.annotations.NullMarked;
import top.focess.veto.api.plugin.agent.AgentProfile;
import top.focess.veto.api.plugin.agent.IsolatedAgent;
import top.focess.veto.api.plugin.contract.JsonValue;

@NullMarked
@DefaultQualifier(
        value = NonNull.class,
        locations = {
            TypeUseLocation.FIELD,
            TypeUseLocation.PARAMETER,
            TypeUseLocation.RETURN,
            TypeUseLocation.UPPER_BOUND
        })
public record ReaderConfig(Map<String, JsonValue> values) {
    public ReaderConfig {
        values = Map.copyOf(values);
    }

    public static ReaderConfig defaults() {
        return new ReaderConfig(Map.of());
    }

    private String text(String key, String fallback) {
        var v = values.get(key);
        return v instanceof JsonValue.StringValue s ? s.value() : fallback;
    }

    private int number(String key, int fallback) {
        var value = values.get(key);
        try {
            if (value instanceof JsonValue.NumberValue number)
                return number.value().intValueExact();
            return Integer.parseInt(text(key, Integer.toString(fallback)));
        } catch (ArithmeticException | NumberFormatException error) {
            throw new IllegalArgumentException("Invalid integer reader setting: " + key, error);
        }
    }

    public IsolatedAgent.Spec spec() {
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

    private static AgentProfile.Prompt prompt(String name) {
        return new AgentProfile.Prompt(name, new JsonValue.ObjectValue(Map.of()));
    }
}
