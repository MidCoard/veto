package top.focess.veto.api.plugin;

import java.util.Set;
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
    /**
     * Returns the stable installed identity used for provenance, namespaces, and lifecycle
     * ownership.
     *
     * @return the installed plugin identity
     */
    @NonNull PluginIdentity identity();

    /**
     * Former stable IDs that the host may resolve to {@link #identity()} when reading durable
     * selections created by an older plugin release. Aliases must be globally unique across all
     * installed plugins and must not equal any plugin's current ID; the host rejects collisions at
     * activation. New selections always persist the current identity. An empty set means that the
     * plugin has never changed its stable ID.
     *
     * @return immutable historical plugin identities owned by this plugin
     */
    default @NonNull Set<@NonNull String> historicalIds() {
        return Set.of();
    }

    /**
     * Stages this plugin's complete contribution batch during single-threaded startup.
     * Implementations may capture the context but must not start threads or perform external
     * effects. Named services are not discoverable until all plugins finish this callback.
     *
     * @param context host-granted services and lifecycle state for this instance
     * @param configuration immutable plugin configuration
     * @return the complete set of contributions to validate and publish
     * @throws PluginFailure when initialization cannot produce a valid contribution set
     */
    @NonNull PluginContributions initialize(
            @NonNull PluginContext context, JsonValue.@NonNull ObjectValue configuration)
            throws PluginFailure;

    /**
     * Starts owned resources after all contributions and named services have been validated.
     *
     * @throws PluginFailure when the plugin cannot start
     */
    void start() throws PluginFailure;

    /**
     * Admission has closed. Cancel plugin-owned blocking waits without waiting for handlers to
     * drain; ordinary resources remain usable by admitted handlers until close. Called once on the
     * lifecycle executor, including failure and partial initialization.
     *
     * @throws PluginFailure when the plugin cannot prepare for shutdown
     */
    default void stopping() throws PluginFailure {}

    /**
     * Releases owned resources once admitted calls have drained; must tolerate partial startup.
     *
     * @throws PluginFailure when an owned resource cannot be released
     */
    @Override
    void close() throws PluginFailure;
}
