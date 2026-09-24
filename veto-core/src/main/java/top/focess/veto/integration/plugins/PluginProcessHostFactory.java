package top.focess.veto.integration.plugins;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.storage.PluginStorage;
import top.focess.veto.api.process.ProcessHost;
import top.focess.veto.plugin.runtime.ManagedPlugin;

public interface PluginProcessHostFactory {
    @NonNull ProcessHost bind(@NonNull ManagedPlugin plugin, @NonNull PluginStorage storage);
}
