package top.focess.veto.api.event;

/**
 * Marker for a class whose {@link EventHandler} methods subscribe to events.
 *
 * <p>A plugin contributes a {@code Listener} through its contribution batch; the host registers the
 * handlers and invokes each one under that plugin's lifecycle admission and only for sessions that
 * select the plugin. A listener never acquires authority by observing an event.
 */
public abstract class Listener {
    /** Constructs an event-listener aspect. */
    protected Listener() {}
}
