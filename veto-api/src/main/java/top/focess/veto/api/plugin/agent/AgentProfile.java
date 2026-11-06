package top.focess.veto.api.plugin.agent;

import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.llm.LlmBinding;
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
 * @param model explicit provider binding, mutually exclusive with a tier
 */
public record AgentProfile(
        @NonNull String name,
        @NonNull String description,
        @NonNull String label,
        @NonNull Set<@NonNull String> tools,
        String tier,
        Prompt prompt,
        @NonNull Map<@NonNull String, @NonNull String> metadata,
        LlmBinding model) {
    /** Creates a profile whose model is selected from the parent's binding or a model tier. */
    public AgentProfile(
            @NonNull String name,
            @NonNull String description,
            @NonNull String label,
            @NonNull Set<@NonNull String> tools,
            String tier,
            Prompt prompt,
            @NonNull Map<@NonNull String, @NonNull String> metadata) {
        this(name, description, label, tools, tier, prompt, metadata, null);
    }

    /**
     * Reference to a host-resolved prompt resource and its interpolation data.
     *
     * @param resource prompt resource identifier
     * @param data structured values supplied to the prompt
     */
    public record Prompt(@NonNull String resource, JsonValue.@NonNull ObjectValue data) {}

    /** Defensively copies the requested tools and metadata. */
    public AgentProfile {
        if (tier != null && model != null)
            throw new IllegalArgumentException("Choose a model tier or an explicit model binding");
        tools = Set.copyOf(tools);
        metadata = Map.copyOf(metadata);
    }
}
