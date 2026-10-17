package top.focess.veto.plugin.runtime;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.contract.AgentInbox;

/** An inbox contribution and the plugin activation that owns its callbacks. */
public record PluginInboxSource(
        @NonNull String id, @NonNull ManagedPlugin plugin, @NonNull AgentInbox source) {}
