package top.focess.veto.api.plugin.contract;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.plugin.agent.AgentHost;
import top.focess.veto.api.plugin.agent.AgentProfile;
import top.focess.veto.api.plugin.storage.PluginStorage;

/** Activation, profile refresh and successful tool boundaries share the same feature policy. */
public interface AgentConfiguration {
    /**
     * Host-authorized tool visible to configuration policy.
     *
     * @param name effective tool name
     * @param capability host capability classification
     * @param pluginId contributing plugin ID, or {@code null} for a host tool
     * @param localId source-local contribution ID, or {@code null} for a host tool
     */
    record Tool(
            @NonNull String name,
            @NonNull ToolCapability capability,
            @Nullable String pluginId,
            @Nullable String localId) {
        /**
         * Creates metadata for a host tool without plugin provenance.
         *
         * @param name effective tool name
         * @param capability host capability classification
         */
        public Tool(@NonNull String name, @NonNull ToolCapability capability) {
            this(name, capability, null, null);
        }
    }

    /**
     * Immutable inputs for one configuration evaluation.
     *
     * @param owner authenticated owner
     * @param scope host-issued session scope
     * @param agents agent authority bound to that scope
     * @param agentId current agent
     * @param base host base profile
     * @param authorizedTools tools already admitted by the host
     * @param activeTask current task text
     */
    record Context(
            @NonNull String owner,
            PluginStorage.@NonNull SessionScope scope,
            AgentHost.@NonNull Session agents,
            @NonNull String agentId,
            @NonNull AgentProfile base,
            @NonNull List<Tool> authorizedTools,
            @NonNull String activeTask) {
        /** Defensively copies the authorized tool view. */
        public Context {
            authorizedTools = List.copyOf(authorizedTools);
        }
    }

    /**
     * Optional plugin-defined profile transition prompt.
     *
     * @param key stable transition key
     * @param prompt transition prompt text
     * @param data structured transition data
     */
    record Transition(
            @NonNull String key, @NonNull String prompt, JsonValue.@NonNull ObjectValue data) {}

    /**
     * Requested effective profile and optional transition.
     *
     * @param profile requested profile, still subject to host authorization
     * @param transition optional transition metadata
     */
    record Intent(@NonNull AgentProfile profile, @Nullable Transition transition) {}

    /**
     * Evaluates the effective profile for the current task.
     *
     * @param context immutable host-authorized configuration inputs
     * @return requested profile intent, or {@code null} to retain the host base configuration
     */
    @Nullable Intent configure(@NonNull Context context);
}
