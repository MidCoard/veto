package top.focess.veto.api.plugin.agent;

import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.veto.api.plugin.contract.JsonValue;

/**
 * Immutable child-agent intent. Tool names and model tier remain subject to host authorization.
 *
 * @param name stable profile name
 * @param description human-readable purpose
 * @param label short display label
 * @param tools requested tool names; copied on construction
 * @param tier requested model tier, or {@code null} for host selection
 * @param prompt optional structured prompt reference
 * @param metadata plugin-defined immutable metadata copied on construction
 */
public record AgentProfile(
        @NonNull String name,
        @NonNull String description,
        @NonNull String label,
        @NonNull Set<String> tools,
        @Nullable String tier,
        @Nullable Prompt prompt,
        @NonNull Map<String, String> metadata) {
    /**
     * Reference to a host-resolved prompt resource and its interpolation data.
     *
     * @param resource prompt resource identifier
     * @param data structured values supplied to the prompt
     */
    public record Prompt(@NonNull String resource, JsonValue.@NonNull ObjectValue data) {}

    /** Defensively copies the requested tools and metadata. */
    public AgentProfile {
        tools = Set.copyOf(tools);
        metadata = Map.copyOf(metadata);
    }
}
