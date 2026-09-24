package top.focess.veto.plugin.runtime;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.workflow.PluginWork;
import top.focess.veto.api.plugin.contract.PluginFailure;

/** Deferred work retains the submitting plugin's lifecycle admission. */
public final class ManagedPluginWork implements PluginWork {
    private final @NonNull ManagedPlugin plugin;
    private final @NonNull PluginWork delegate;

    public ManagedPluginWork(@NonNull ManagedPlugin plugin, @NonNull PluginWork delegate) {
        this.plugin = plugin;
        this.delegate = delegate;
    }

    @Override
    public void run(@NonNull Runtime runtime) {
        try {
            plugin.execute(
                    () -> {
                        delegate.run(runtime);
                        return true;
                    });
        } catch (PluginFailure failure) {
            throw new IllegalStateException("Submitting plugin is unavailable", failure);
        }
    }
}
