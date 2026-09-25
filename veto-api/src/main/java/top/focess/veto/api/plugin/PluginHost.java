package top.focess.veto.api.plugin;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.veto.api.agent.workflow.PluginAwait;
import top.focess.veto.api.plugin.contract.JsonValue;

/**
 * Host-mediated effects for operator-trusted plugins.
 *
 * <p>A host may grant this Java service through {@link PluginContext#service(Class)}. Each method
 * applies its own current scope, selection, lifecycle, and invocation checks. Retaining this object
 * is not retained authorization, and this interface does not sandbox arbitrary Java code.
 */
public interface PluginHost {
    /**
     * Schedules work after host startup migrations. Callback execution context is host-defined; the
     * callback must still obtain current admission for every effect.
     */
    default void whenReady(@NonNull Runnable callback) {
        callback.run();
    }

    /** Registers foreground work owned by the current authorized invocation of {@code tool}. */
    default void await(@NonNull String tool, @NonNull PluginAwait wait) {
        throw new IllegalStateException("Foreground waits are unavailable");
    }

    record Invocation(
            @NonNull String owner,
            @NonNull String sessionId,
            @NonNull String agentId,
            @Nullable String requestId,
            @NonNull String callId) {}

    /**
     * Returns host-derived invocation facts for a live authorized call of {@code tool}; claimed
     * identifiers and stale calls are rejected.
     */
    @NonNull Invocation invocation(@NonNull String tool);

    /**
     * Hint only; host recovery, selection, pause, approval, and budget gates remain authoritative.
     */
    void wake(@NonNull String owner, @NonNull String sessionId, @NonNull String agentId);

    /** Publishes plugin facts only to an authorized, currently selected session. */
    default void publish(
            @NonNull String sessionId,
            @NonNull String topic,
            JsonValue.@NonNull ObjectValue facts) {
        throw new IllegalStateException("Plugin event publication is unavailable");
    }

    /** Invalidates a plugin-owned resource for an authorized session. */
    void invalidate(@NonNull String sessionId, @NonNull String resource);
}
