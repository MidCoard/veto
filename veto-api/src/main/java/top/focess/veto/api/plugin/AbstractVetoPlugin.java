package top.focess.veto.api.plugin;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.PluginFailure;

/**
 * Failure-sanitizing base class for plugin lifecycle callbacks.
 *
 * <p>The host owns scheduling, state, admission, and cleanup ordering. Undeclared callback
 * exceptions are converted to {@link PluginFailure.Code#INTERNAL_FAILURE}, so implementation
 * details and causes do not cross the public lifecycle boundary.
 */
public abstract class AbstractVetoPlugin implements VetoPlugin {
    /** Creates a plugin instance for host-managed lifecycle callbacks. */
    protected AbstractVetoPlugin() {}

    /**
     * Returns the stable identity used to register this plugin.
     *
     * @return this plugin's identity
     */
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
    public final void stopping() throws PluginFailure {
        try {
            onStopping();
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

    /**
     * Implements initialization under the same restrictions as {@link VetoPlugin#initialize}.
     *
     * @param context host-bound services for this plugin instance
     * @param configuration immutable JSON configuration supplied by the host
     * @return the complete contribution batch
     * @throws Exception when initialization cannot complete
     */
    protected abstract @NonNull PluginContributions onInitialize(
            @NonNull PluginContext context, JsonValue.@NonNull ObjectValue configuration)
            throws Exception;

    /**
     * Starts plugin-owned resources after contribution validation and service binding.
     *
     * @throws Exception when the resources cannot be started
     */
    protected abstract void onStart() throws Exception;

    /**
     * Cancels plugin-owned blocking waits after admission closes.
     *
     * @throws Exception when shutdown preparation fails
     */
    protected void onStopping() throws Exception {}

    /**
     * Releases plugin-owned resources and tolerates partial initialization.
     *
     * @throws Exception when a resource cannot be released
     */
    protected abstract void onClose() throws Exception;
}
