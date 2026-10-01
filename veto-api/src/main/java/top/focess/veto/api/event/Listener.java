package top.focess.veto.api.event;

/**
 * Marker for a class whose {@link EventHandler} methods subscribe to events.
 *
 * <p>A plugin registers a complete {@code Listener} with {@code PluginContext.register} at the
 * listeners contribution point. The host invokes workflow handlers under the contributing plugin's
 * lifecycle admission for sessions that select it; lifecycle notifications reach active plugins. A
 * listener never acquires authority by observing an event.
 */
public abstract class Listener {
    /** Constructs an event-listener aspect. */
    protected Listener() {}
}
