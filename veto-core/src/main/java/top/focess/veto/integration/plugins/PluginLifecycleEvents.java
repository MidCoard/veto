package top.focess.veto.integration.plugins;

import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.contract.PluginFailure;
import top.focess.veto.api.plugin.contract.SessionLifecycle;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.plugin.runtime.*;

/**
 * Dispatches owner/session/agent lifecycle transitions to every ACTIVE plugin's {@code
 * veto:session-lifecycle} contributions. Notifications are best-effort: a failing or inactive
 * plugin is logged and skipped so logout, session deletion, and agent termination never break.
 */
@Service
public class PluginLifecycleEvents {
    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.integration.plugins.PluginLifecycleEvents");

    private final @NonNull PluginManager manager;

    public PluginLifecycleEvents(@NonNull PluginManager manager) {
        this.manager = manager;
    }

    public void ownerOpened(@NonNull String ownerId) {
        dispatch(lifecycle -> lifecycle.onOwnerOpen(ownerId));
    }

    public void ownerClosed(@NonNull String ownerId) {
        dispatch(lifecycle -> lifecycle.onOwnerClosed(ownerId));
    }

    public void sessionClosed(@NonNull String ownerId, @NonNull String sessionId) {
        dispatch(lifecycle -> lifecycle.onSessionClosed(ownerId, sessionId));
    }

    public void agentTerminated(
            @NonNull String ownerId, @NonNull String sessionId, @NonNull String agentId) {
        dispatch(lifecycle -> lifecycle.onAgentTerminated(ownerId, sessionId, agentId));
    }

    private void dispatch(@NonNull Consumer<@NonNull SessionLifecycle> notification) {
        for (var entry : manager.catalog().entries(StandardContributionPoints.SESSION_LIFECYCLE)) {
            var plugin = manager.plugin(entry.source().namespace());
            if (plugin.state() != PluginState.ACTIVE) continue;
            try {
                plugin.execute(
                        () -> {
                            notification.accept(entry.implementation());
                            return true;
                        });
            } catch (PluginFailure | RuntimeException failure) {
                // Best-effort: a failing plugin must never break logout, session deletion, or
                // agent termination. Runtime exceptions from the notification itself are included.
                log.warn(
                        "Plugin lifecycle notification failed for {}",
                        plugin.identity().id(),
                        failure);
            }
        }
    }
}
