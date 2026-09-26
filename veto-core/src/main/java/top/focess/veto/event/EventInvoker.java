package top.focess.veto.event;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.event.Event;
import top.focess.veto.api.event.Listener;

/**
 * A compiled event handler call. Built once at registration from the reflective method handle, so
 * dispatch is a plain interface call with no per-invocation reflection.
 */
@FunctionalInterface
public interface EventInvoker {
    /**
     * Invokes the handler on the listener with the event.
     *
     * @param listener the object declaring the handler
     * @param event the event to handle
     * @throws Exception if the handler body throws
     */
    void invoke(@NonNull Listener listener, @NonNull Event event) throws Exception;
}
