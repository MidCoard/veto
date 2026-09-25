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
     *
     * @param callback work to run when the host is ready
     */
    default void whenReady(@NonNull Runnable callback) {
        callback.run();
    }

    /**
     * Registers foreground work owned by the current authorized invocation of {@code tool}.
     *
     * @param tool contributed tool name used for current-invocation admission
     * @param wait wait registration whose lifecycle is owned by the invocation
     */
    default void await(@NonNull String tool, @NonNull PluginAwait wait) {
        throw new IllegalStateException("Foreground waits are unavailable");
    }

    /**
     * Host-derived identity of an admitted tool call.
     *
     * @param owner authenticated owner ID
     * @param sessionId selected session ID
     * @param agentId executing agent ID
     * @param requestId durable request ID, or {@code null} when none exists
     * @param callId unique tool-call ID
     */
    record Invocation(
            @NonNull String owner,
            @NonNull String sessionId,
            @NonNull String agentId,
            @Nullable String requestId,
            @NonNull String callId) {}

    /**
     * Returns host-derived invocation facts for a live authorized call of {@code tool}; claimed
     * identifiers and stale calls are rejected.
     *
     * @param tool contributed tool name expected for the current call
     * @return authenticated invocation facts
     */
    @NonNull Invocation invocation(@NonNull String tool);

    /**
     * Hint only; host recovery, selection, pause, approval, and budget gates remain authoritative.
     *
     * @param owner owner containing the target agent
     * @param sessionId target session
     * @param agentId target agent
     */
    void wake(@NonNull String owner, @NonNull String sessionId, @NonNull String agentId);

    /**
     * Publishes plugin facts only to an authorized, currently selected session.
     *
     * @param sessionId target session
     * @param topic plugin-defined event topic
     * @param facts JSON event facts
     */
    default void publish(
            @NonNull String sessionId,
            @NonNull String topic,
            JsonValue.@NonNull ObjectValue facts) {
        throw new IllegalStateException("Plugin event publication is unavailable");
    }

    /**
     * Invalidates a plugin-owned resource for an authorized session.
     *
     * @param sessionId session whose resource changed
     * @param resource plugin-defined resource identifier
     */
    void invalidate(@NonNull String sessionId, @NonNull String resource);
}
