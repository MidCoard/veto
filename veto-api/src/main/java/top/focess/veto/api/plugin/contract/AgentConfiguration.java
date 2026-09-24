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
    record Tool(
            @NonNull String name,
            @NonNull ToolCapability capability,
            @Nullable String pluginId,
            @Nullable String localId) {
        public Tool(@NonNull String name, @NonNull ToolCapability capability) {
            this(name, capability, null, null);
        }
    }

    record Context(
            @NonNull String owner,
            PluginStorage.@NonNull SessionScope scope,
            AgentHost.@NonNull Session agents,
            @NonNull String agentId,
            @NonNull AgentProfile base,
            @NonNull List<Tool> authorizedTools,
            @NonNull String activeTask) {
        public Context {
            authorizedTools = List.copyOf(authorizedTools);
        }
    }

    record Transition(
            @NonNull String key, @NonNull String prompt, JsonValue.@NonNull ObjectValue data) {}

    record Intent(@NonNull AgentProfile profile, @Nullable Transition transition) {}

    /** Null leaves the host's base configuration unchanged. */
    @Nullable Intent configure(@NonNull Context context);
}
