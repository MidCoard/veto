package top.focess.veto.builtin.group;

import java.util.Map;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.contract.JsonValue;

/** Immutable plugin settings. Model-call accounting remains exclusively host-owned. */
public record GroupConfig(@NonNull Map<String, JsonValue> values) {
    public GroupConfig {
        values = Map.copyOf(values);
    }

    /** Configuration with no overrides; every setting falls back to its built-in default. */
    public static @NonNull GroupConfig defaults() {
        return new GroupConfig(Map.of());
    }

    /** Group orchestration tick interval in milliseconds; always positive. */
    public long tickMillis() {
        long value = Long.parseLong(text("group-tick-interval-ms", "1000"));
        if (value < 1) throw new IllegalArgumentException("Group tick interval must be positive");
        return value;
    }

    /** Model tier for the Leader, or for a Mate with the given legacy skillset label. */
    public @NonNull String tier(boolean leader, String legacySkillset) {
        String base =
                text(leader ? "group-leader-tier" : "group-mate-tier", leader ? "TOP" : "MID");
        return !leader && legacySkillset != null
                ? text("group-skillset." + legacySkillset + ".tier", base)
                : base;
    }

    /** Extra prompt guidance for the Leader, or for a Mate with the given legacy skillset label. */
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
