package top.focess.veto.api.plugin;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.veto.api.agent.workflow.PluginAwait;
import top.focess.veto.api.plugin.contract.JsonValue;

/** Generic host effects for operator-trusted plugins. This interface is not a sandbox. */
public interface PluginHost {
    /** Once the host has completed startup migrations. */
    default void whenReady(@NonNull Runnable callback) {
        callback.run();
    }

    /** Register foreground work from an authorized tool invocation. */
    default void await(@NonNull String tool, @NonNull PluginAwait wait) {
        throw new IllegalStateException("Foreground waits are unavailable");
    }

    record Invocation(
            @NonNull String owner,
            @NonNull String sessionId,
            @NonNull String agentId,
            @Nullable String requestId,
            @NonNull String callId) {}

    /** Requires a live, host-authorized tool invocation. */
    @NonNull Invocation invocation(@NonNull String tool);

    /** Hint only: host recovery, pause, approval and budget gates remain authoritative. */
    void wake(@NonNull String owner, @NonNull String sessionId, @NonNull String agentId);

    /** Publish plugin facts to an authorized selected session through the generic transport. */
    default void publish(
            @NonNull String sessionId,
            @NonNull String topic,
            JsonValue.@NonNull ObjectValue facts) {
        throw new IllegalStateException("Plugin event publication is unavailable");
    }

    void invalidate(@NonNull String sessionId, @NonNull String resource);
}
