package top.focess.veto.api.plugin;

import java.util.Set;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.contract.PluginFailure;

/**
 * One trusted, installed plugin instance. The host discovers enabled packages at backend startup,
 * then calls each Java entry's public {@code (PluginContext, JsonValue.ObjectValue)} constructor.
 * The constructor can register aspects through {@link PluginContext#register}. The host constructs
 * every admitted plugin before calling any plugin's {@link #start()}, so services and contribution
 * points registered during construction can be found from {@code start()}.
 *
 * <p>The host validates the catalog, calls {@code start()} once, and admits calls only after it
 * succeeds. On backend shutdown or startup failure after an instance exists, the host closes
 * admission and calls {@link #stopping()} to unblock waits. During graceful shutdown it lets
 * already admitted calls finish, then calls {@link #close()} once, closes registered resources, and
 * releases the package loader. A fatal plugin failure can begin cleanup immediately. If the
 * constructor throws before an instance exists, these instance methods cannot run; the host still
 * closes resources already registered with the context.
 *
 * <p>Management enable/disable choices apply at the next backend startup; they do not restart this
 * instance while the backend runs. Host adapters sanitize unchecked failures so only {@link
 * PluginFailure} codes are public.
 */
public abstract class VetoPlugin implements AutoCloseable {
    /** Creates the base instance; the concrete entry constructor receives the bound context. */
    protected VetoPlugin() {}

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
    public String preferredToolName(@NonNull String localId) {
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
     * Starts this instance once after all admitted plugins have been constructed and the initial
     * catalog has been validated. Cross-plugin service discovery belongs here or in later work.
     * Returning successfully allows the host to mark the instance active and admit calls. If this
     * method fails, the host signals {@link #stopping()} and performs cleanup.
     *
     * @throws PluginFailure when the plugin cannot start
     */
    public abstract void start() throws PluginFailure;

    /**
     * Called at most once when admission closes, including backend shutdown, startup failure after
     * construction, and fatal plugin failure. Cancel plugin-owned blocking waits here without
     * waiting for admitted handlers to drain. On graceful shutdown, resources used by those
     * handlers remain available until {@link #close()}. This callback runs on the host lifecycle
     * executor and may run even if {@link #start()} was never called or did not finish.
     *
     * @throws PluginFailure when the plugin cannot prepare for shutdown
     */
    public void stopping() throws PluginFailure {}

    /**
     * Releases instance-owned resources at most once. On graceful shutdown the host first waits for
     * admitted calls to drain; on fatal failure cleanup may begin sooner. This method must tolerate
     * a constructed instance whose {@link #start()} never succeeded. After it returns, the host
     * closes resources registered with the context and releases the package loader.
     *
     * @throws PluginFailure when an owned resource cannot be released
     */
    @Override
    public abstract void close() throws PluginFailure;
}
