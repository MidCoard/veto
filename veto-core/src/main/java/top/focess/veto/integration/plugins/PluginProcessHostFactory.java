package top.focess.veto.integration.plugins;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.storage.PluginStorage;
import top.focess.veto.api.process.ProcessHost;
import top.focess.veto.plugin.runtime.ManagedPlugin;

/** Host-only binding factory for the per-plugin process host; never a plugin-visible service. */
public interface PluginProcessHostFactory {
    /** Creates the process host bound to the given plugin activation and its scoped storage. */
    @NonNull ProcessHost bind(@NonNull ManagedPlugin plugin, @NonNull PluginStorage storage);
}
