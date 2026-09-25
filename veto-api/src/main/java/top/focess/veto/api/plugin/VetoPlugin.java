package top.focess.veto.api.plugin;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.PluginFailure;

/**
 * Trusted, startup-only plugin. A public no-argument constructor is required. Initialize stages
 * contributions without external effects; start makes the instance ready. The host publishes only
 * after successful start. Close must tolerate partial initialization, revoke handlers and release
 * owned resources; the host calls it even after startup failure. Host adapters must sanitize
 * unchecked failures too; only PluginFailure codes are public.
 */
public interface VetoPlugin extends AutoCloseable {
    /** Major host SPI version implemented by this API. */
    int API_VERSION = 1;

    /** Stable installed identity used for provenance, namespaces, and lifecycle ownership. */
    @NonNull PluginIdentity identity();

    /**
     * Stages this plugin's complete contribution batch during single-threaded startup.
     * Implementations may capture the context but must not start threads or perform external
     * effects. Named services are not discoverable until all plugins finish this callback.
     */
    @NonNull PluginContributions initialize(
            @NonNull PluginContext context, JsonValue.@NonNull ObjectValue configuration)
            throws PluginFailure;

    /** Starts owned resources after all contributions and named services have been validated. */
    void start() throws PluginFailure;

    /**
     * Admission has closed. Cancel plugin-owned blocking waits without waiting for handlers to
     * drain; ordinary resources remain usable by admitted handlers until close. Called once on the
     * lifecycle executor, including failure and partial initialization.
     */
    default void stopping() throws PluginFailure {}

    /** Releases owned resources once admitted calls have drained; must tolerate partial startup. */
    @Override
    void close() throws PluginFailure;
}
