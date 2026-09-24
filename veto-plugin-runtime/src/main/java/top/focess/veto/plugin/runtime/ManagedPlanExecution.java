package top.focess.veto.plugin.runtime;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.veto.api.agent.workflow.ActionsProgram;
import top.focess.veto.api.agent.workflow.PlanExecution;
import top.focess.veto.api.agent.workflow.Scope;
import top.focess.veto.api.plugin.contract.PluginFailure;

/** Deferred execution retains the submitting plugin's lifecycle admission. */
public final class ManagedPlanExecution implements PlanExecution {
    private final @NonNull ManagedPlugin plugin;
    private final @NonNull PlanExecution delegate;

    public ManagedPlanExecution(@NonNull ManagedPlugin plugin, @NonNull PlanExecution delegate) {
        this.plugin = plugin;
        this.delegate = delegate;
    }

    @Override
    public void configure(int maxSteps) {
        invoke(
                () -> {
                    delegate.configure(maxSteps);
                    return true;
                });
    }

    @Override
    public @NonNull Scope scope() {
        return invoke(delegate::scope);
    }

    @Override
    public boolean active() {
        return invoke(delegate::active);
    }

    @Override
    public void install(@NonNull ActionsProgram program, @Nullable String modelCallId) {
        invoke(
                () -> {
                    delegate.install(program, modelCallId);
                    return true;
                });
    }

    @Override
    public void step(@NonNull Runtime runtime) {
        invoke(
                () -> {
                    delegate.step(runtime);
                    return true;
                });
    }

    @Override
    public void run(@NonNull Runtime runtime) {
        invoke(
                () -> {
                    delegate.run(runtime);
                    return true;
                });
    }

    private <T extends @NonNull Object> @NonNull T invoke(
            ManagedPlugin.@NonNull Operation<T> operation) {
        try {
            return plugin.execute(operation);
        } catch (PluginFailure failure) {
            throw new IllegalStateException("Submitting plugin is unavailable", failure);
        }
    }
}
