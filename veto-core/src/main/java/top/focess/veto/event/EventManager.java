package top.focess.veto.event;

import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import top.focess.veto.api.event.Event;
import top.focess.veto.integration.plugins.PluginManager;
import top.focess.veto.integration.plugins.SessionPlugins;
import top.focess.veto.integration.plugins.storage.PluginInvocationContext;

/**
 * Shared host entry point for synchronous event delivery. The submitting thread's current session
 * selects its available pinned plugins; outside a session, matching active plugins receive events.
 * Each submission captures one prepared publication; every recipient still passes current plugin
 * lifecycle admission. Event payloads do not select delivery policy.
 *
 * <p>Concurrent submissions are supported. Handlers for one event run serially on its producer's
 * thread; different events may reach the same listener concurrently. Producers own their mutable
 * event until submit returns. This service supplies no queue or listener-instance lock.
 */
@Service
public final class EventManager {
    private final @NonNull PluginManager plugins;
    private final @NonNull SessionPlugins selections;

    /** Creates the host dispatcher over published routes and session selection. */
    public EventManager(@NonNull PluginManager plugins, @NonNull SessionPlugins selections) {
        this.plugins = plugins;
        this.selections = selections;
    }

    /** Delivers the event inline using the submitting thread's host invocation context. */
    public void submit(@NonNull Event event) {
        var publication = plugins.snapshot();
        var routes = publication.events();
        if (!routes.hasHandlers(event)) return;
        Set<String> selected = null;
        var sessionId = PluginInvocationContext.currentSession();
        if (sessionId != null) selected = selections.selectedIds(sessionId, publication);
        routes.submit(event, selected);
    }
}
