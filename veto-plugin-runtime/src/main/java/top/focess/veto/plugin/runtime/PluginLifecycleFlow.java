package top.focess.veto.plugin.runtime;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.workflow.ModelFlow;
import top.focess.veto.api.plugin.contract.PluginFailure;

/** A selected model flow retains the submitting plugin's lifecycle admission. */
public final class PluginLifecycleFlow implements ModelFlow {
    private final @NonNull PluginLifecycle plugin;
    private final @NonNull ModelFlow delegate;

    /**
     * Binds a selected flow to its submitting plugin so execution re-acquires that plugin's
     * admission.
     */
    public PluginLifecycleFlow(@NonNull PluginLifecycle plugin, @NonNull ModelFlow delegate) {
        this.plugin = plugin;
        this.delegate = delegate;
    }

    /** Binds a nested flow to the same plugin activation as its parent. */
    public @NonNull PluginLifecycleFlow child(@NonNull ModelFlow work) {
        return new PluginLifecycleFlow(plugin, work);
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
