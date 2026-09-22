package top.focess.veto.plugin.api;

import org.jspecify.annotations.NonNull;
import top.focess.veto.plugin.contract.JsonValue;
import top.focess.veto.plugin.contract.PluginFailure;

/**
 * Trusted, startup-only plugin. A public no-argument constructor is required. Initialize stages
 * contributions without external effects; start makes the instance ready. The host publishes only
 * after successful start. Close must tolerate partial initialization, revoke handlers and release
 * owned resources; the host calls it even after startup failure. Host adapters must sanitize
 * unchecked failures too; only PluginFailure codes are public.
 */
public interface VetoPlugin extends AutoCloseable {
    int API_VERSION = 1;

    @NonNull PluginIdentity identity();

    @NonNull PluginContributions initialize(
            @NonNull PluginContext context, JsonValue.@NonNull ObjectValue configuration)
            throws PluginFailure;

    void start() throws PluginFailure;

    @Override
    void close() throws PluginFailure;
}
