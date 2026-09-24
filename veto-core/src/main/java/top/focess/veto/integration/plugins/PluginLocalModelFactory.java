package top.focess.veto.integration.plugins;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.llm.LocalModelCompletion;
import top.focess.veto.plugin.runtime.ManagedPlugin;

/** Host-only identity/lifecycle binding, removed before a plugin receives services. */
@FunctionalInterface
public interface PluginLocalModelFactory {
    @NonNull LocalModelCompletion bind(@NonNull ManagedPlugin plugin);
}
