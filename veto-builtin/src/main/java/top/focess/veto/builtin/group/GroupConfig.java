package top.focess.veto.builtin.group;

import java.util.Map;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.contract.JsonValue;

/** Immutable plugin settings. Model-call accounting remains exclusively host-owned. */
public record GroupConfig(@NonNull Map<String, JsonValue> values) {
    public GroupConfig {
        values = Map.copyOf(values);
    }

    public static @NonNull GroupConfig defaults() {
        return new GroupConfig(Map.of());
    }

    public long tickMillis() {
        long value = Long.parseLong(text("group-tick-interval-ms", "1000"));
        if (value < 1) throw new IllegalArgumentException("Group tick interval must be positive");
        return value;
    }

    public @NonNull String tier(boolean leader, String legacySkillset) {
        String base =
                text(leader ? "group-leader-tier" : "group-mate-tier", leader ? "TOP" : "MID");
        return !leader && legacySkillset != null
                ? text("group-skillset." + legacySkillset + ".tier", base)
                : base;
    }

    public @NonNull String guidance(boolean leader, String legacySkillset) {
        String base = text(leader ? "group-leader-guidance" : "group-mate-guidance", "");
        return !leader && legacySkillset != null
                ? text("group-skillset." + legacySkillset + ".guidance", base)
                : base;
    }

    private @NonNull String text(@NonNull String key, @NonNull String fallback) {
        var value = values.get(key);
        return value instanceof JsonValue.StringValue text ? text.value() : fallback;
    }
}
