package top.focess.veto.integration.plugins;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.agent.AgentHost;
import top.focess.veto.api.plugin.storage.PluginStorage;
import top.focess.veto.plugin.runtime.ManagedPlugin;

/** Host-only lifecycle binding; never exposed as a plugin service. */
public interface PluginAgentHostFactory {
    @NonNull AgentHost bind(@NonNull ManagedPlugin plugin, @NonNull PluginStorage storage);
}
