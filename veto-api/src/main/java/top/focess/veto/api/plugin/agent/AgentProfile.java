package top.focess.veto.api.plugin.agent;

import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.veto.api.plugin.contract.JsonValue;

/** Immutable intent. Tool names and model tier remain subject to host authorization. */
public record AgentProfile(
        @NonNull String name,
        @NonNull String description,
        @NonNull String label,
        @NonNull Set<String> tools,
        @Nullable String tier,
        @Nullable Prompt prompt,
        @NonNull Map<String, String> metadata) {
    public record Prompt(@NonNull String resource, JsonValue.@NonNull ObjectValue data) {}

    public AgentProfile {
        tools = Set.copyOf(tools);
        metadata = Map.copyOf(metadata);
    }
}
