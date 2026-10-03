package top.focess.veto.api.event;

/**
 * Marker for a class whose {@link EventHandler} methods subscribe to events.
 *
 * <p>A plugin registers a complete {@code Listener} with {@code PluginContext.register} at the
 * listeners contribution point. The host invokes handlers under the contributing plugin's lifecycle
 * admission, using the submitting thread's session context to select recipients. A listener never
 * acquires authority by observing an event.
 *
 * <p>Handlers for one dispatch run serially on its producer's thread. Different dispatches may
 * invoke this instance concurrently, so listener-owned shared state requires its own coordination.
 * A handler must not retain an {@link Event} for asynchronous mutation.
 */
public interface Listener {}
