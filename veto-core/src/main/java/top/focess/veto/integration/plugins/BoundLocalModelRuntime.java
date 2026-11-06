package top.focess.veto.integration.plugins;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.llm.LocalModelRuntime;
import top.focess.veto.api.llm.exceptions.ModelCapabilityException;
import top.focess.veto.api.plugin.contract.PluginFailure;
import top.focess.veto.plugin.runtime.ManagedPlugin;

/** Borrowed shared runtime with live plugin admission; the host retains process ownership. */
record BoundLocalModelRuntime(@NonNull ManagedPlugin plugin, @NonNull LocalModelRuntime runtime)
        implements LocalModelRuntime {
    @Override
    public @NonNull String endpoint(@NonNull String modelPath, int contextTokens) {
        try {
            return plugin.execute(() -> runtime.endpoint(modelPath, contextTokens));
        } catch (PluginFailure unavailable) {
            throw new ModelCapabilityException("Model provider is unavailable", unavailable);
        }
    }
}
