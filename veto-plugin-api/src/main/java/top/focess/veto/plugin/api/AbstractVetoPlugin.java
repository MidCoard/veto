package top.focess.veto.plugin.api;

import org.jspecify.annotations.NonNull;
import top.focess.veto.extension.contract.ExtensionFailure;
import top.focess.veto.extension.contract.JsonValue;

/** Plugin callbacks only. The host owns scheduling, state, admission and cleanup ordering. */
public abstract class AbstractVetoPlugin implements VetoPlugin {
    public abstract @NonNull PluginIdentity identity();

    @Override
    public final @NonNull PluginContributions initialize(
            @NonNull PluginContext context, JsonValue.@NonNull ObjectValue configuration)
            throws ExtensionFailure {
        try {
            return onInitialize(context, configuration);
        } catch (Exception failure) {
            throw safe(failure);
        }
    }

    @Override
    public final void start() throws ExtensionFailure {
        try {
            onStart();
        } catch (Exception failure) {
            throw safe(failure);
        }
    }

    @Override
    public final void close() throws ExtensionFailure {
        try {
            onClose();
        } catch (Exception failure) {
            throw safe(failure);
        }
    }

    private static @NonNull ExtensionFailure safe(@NonNull Exception failure) {
        return failure instanceof ExtensionFailure declared
                ? declared
                : new ExtensionFailure(ExtensionFailure.Code.INTERNAL_FAILURE);
    }

    protected abstract @NonNull PluginContributions onInitialize(
            @NonNull PluginContext context, JsonValue.@NonNull ObjectValue configuration)
            throws Exception;

    protected abstract void onStart() throws Exception;

    protected abstract void onClose() throws Exception;
}
