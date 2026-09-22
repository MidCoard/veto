package top.focess.veto.plugin.api;

import org.jspecify.annotations.NonNull;
import top.focess.veto.plugin.contract.JsonValue;
import top.focess.veto.plugin.contract.PluginFailure;

/** Plugin callbacks only. The host owns scheduling, state, admission and cleanup ordering. */
public abstract class AbstractVetoPlugin implements VetoPlugin {
    @Override
    public abstract @NonNull PluginIdentity identity();

    @Override
    public final @NonNull PluginContributions initialize(
            @NonNull PluginContext context, JsonValue.@NonNull ObjectValue configuration)
            throws PluginFailure {
        try {
            return onInitialize(context, configuration);
        } catch (Exception failure) {
            throw safe(failure);
        }
    }

    @Override
    public final void start() throws PluginFailure {
        try {
            onStart();
        } catch (Exception failure) {
            throw safe(failure);
        }
    }

    @Override
    public final void close() throws PluginFailure {
        try {
            onClose();
        } catch (Exception failure) {
            throw safe(failure);
        }
    }

    private static @NonNull PluginFailure safe(@NonNull Exception failure) {
        return failure instanceof PluginFailure declared
                ? declared
                : new PluginFailure(PluginFailure.Code.INTERNAL_FAILURE);
    }

    protected abstract @NonNull PluginContributions onInitialize(
            @NonNull PluginContext context, JsonValue.@NonNull ObjectValue configuration)
            throws Exception;

    protected abstract void onStart() throws Exception;

    protected abstract void onClose() throws Exception;
}
