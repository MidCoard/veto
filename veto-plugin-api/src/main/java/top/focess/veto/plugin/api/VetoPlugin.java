package top.focess.veto.plugin.api;

import org.jspecify.annotations.NonNull;
import top.focess.veto.extension.contract.ExtensionFailure;
import top.focess.veto.extension.contract.JsonValue;

/**
 * Trusted, startup-only plugin. A public no-argument constructor is required. Initialize stages
 * contributions without external effects; start makes the instance ready. The host publishes only
 * after successful start. Close must tolerate partial initialization, revoke handlers and release
 * owned resources; the host calls it even after startup failure. Host adapters must sanitize
 * unchecked failures too; only ExtensionFailure codes are public.
 */
public interface VetoPlugin extends AutoCloseable {
    int API_VERSION = 1;

    @NonNull PluginContributions initialize(
            @NonNull PluginContext context, JsonValue.@NonNull ObjectValue configuration)
            throws ExtensionFailure;

    void start() throws ExtensionFailure;

    @Override
    void close() throws ExtensionFailure;
}
