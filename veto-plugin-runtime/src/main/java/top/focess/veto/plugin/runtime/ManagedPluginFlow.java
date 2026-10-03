package top.focess.veto.plugin.runtime;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.workflow.ModelFlow;
import top.focess.veto.api.plugin.contract.PluginFailure;

/** A selected model flow retains the submitting plugin's lifecycle admission. */
public final class ManagedPluginFlow implements ModelFlow {
    private final @NonNull ManagedPlugin plugin;
    private final @NonNull ModelFlow delegate;

    /**
     * Binds a selected flow to its submitting plugin so execution re-acquires that plugin's
     * admission.
     */
    public ManagedPluginFlow(@NonNull ManagedPlugin plugin, @NonNull ModelFlow delegate) {
        this.plugin = plugin;
        this.delegate = delegate;
    }

    /** Binds a nested flow to the same plugin activation as its parent. */
    public @NonNull ManagedPluginFlow child(@NonNull ModelFlow work) {
        return new ManagedPluginFlow(plugin, work);
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
