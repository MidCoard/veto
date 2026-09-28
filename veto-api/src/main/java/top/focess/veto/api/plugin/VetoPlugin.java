package top.focess.veto.api.plugin;

import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.PluginFailure;

/**
 * Trusted installable plugin. Java package entries have a public constructor accepting {@link
 * PluginContext} and {@link JsonValue.ObjectValue}. The host reads {@link #contributions()} after
 * construction; start makes the instance ready. The host publishes only after successful start.
 * Close releases owned resources, and the host calls it after startup failure when an instance was
 * constructed. Host adapters sanitize unchecked failures so only {@link PluginFailure} codes are
 * public.
 */
public abstract class VetoPlugin implements AutoCloseable {
    /** Creates the plugin before its contribution batch is read. */
    protected VetoPlugin() {}

    /**
     * Returns this instance's complete contribution batch after construction.
     *
     * @return this instance's complete contribution batch after construction
     */
    public abstract @NonNull PluginContributions contributions();

    /**
     * Returns the stable installed identity used for provenance, namespaces, and lifecycle
     * ownership.
     *
     * @return the installed plugin identity
     */
    public abstract @NonNull PluginIdentity identity();

    /**
     * Returns the human-readable name shown when selecting this plugin. This is presentation only;
     * {@link #identity()} remains the stable selection and storage key.
     *
     * @return a nonblank display name, or the stable ID by default
     */
    public @NonNull String displayName() {
        return identity().id();
    }

    /**
     * Optional default public name for a contributed tool. An operator alias has priority; null
     * requests the host's namespaced fallback. Plugins should return a stable name because
     * persisted tool calls refer to it. Name collisions reject activation.
     *
     * @param localId local contribution ID without the plugin namespace
     * @return preferred public tool name, or null for the host fallback
     */
    public @Nullable String preferredToolName(@NonNull String localId) {
        return null;
    }

    /**
     * Former stable IDs that the host may resolve to {@link #identity()} when reading durable
     * selections created by an older plugin release. Aliases must be globally unique across all
     * installed plugins and must not equal any plugin's current ID; the host rejects collisions at
     * activation. New selections always persist the current identity. An empty set means that the
     * plugin has never changed its stable ID.
     *
     * @return immutable historical plugin identities owned by this plugin
     */
    public @NonNull Set<@NonNull String> historicalIds() {
        return Set.of();
    }

    /**
     * Host activation bridge. Constructor-bound Java plugins normally inherit this implementation,
     * which returns {@link #contributions()}. Script-runtime adapters may override it to bind
     * lifecycle callbacks. Named services are not discoverable until activation finishes.
     *
     * @param context host-granted services and lifecycle state for this instance
     * @param configuration immutable plugin configuration
     * @return the complete set of contributions to validate and publish
     * @throws PluginFailure when initialization cannot produce a valid contribution set
     * @throws PluginDeclinedException to intentionally remain inactive before contributions are
     *     published; this is not permitted from {@link #start()}
     */
    public @NonNull PluginContributions initialize(
            @NonNull PluginContext context, JsonValue.@NonNull ObjectValue configuration)
            throws PluginFailure {
        return contributions();
    }

    /**
     * Starts owned resources after all contributions and named services have been validated.
     *
     * @throws PluginFailure when the plugin cannot start
     */
    public abstract void start() throws PluginFailure;

    /**
     * Admission has closed. Cancel plugin-owned blocking waits without waiting for handlers to
     * drain; ordinary resources remain usable by admitted handlers until close. Called once on the
     * lifecycle executor, including failure and partial initialization.
     *
     * @throws PluginFailure when the plugin cannot prepare for shutdown
     */
    public void stopping() throws PluginFailure {}

    /**
     * Releases owned resources once admitted calls have drained; must tolerate partial startup.
     *
     * @throws PluginFailure when an owned resource cannot be released
     */
    @Override
    public abstract void close() throws PluginFailure;
}
