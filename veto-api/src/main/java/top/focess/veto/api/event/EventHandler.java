package top.focess.veto.api.event;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a single-parameter method as an event handler. The handled event type is inferred from the
 * method's sole parameter, which must be a concrete {@link Event} subtype; the host rejects an
 * abstract or non-event parameter at registration.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface EventHandler {
    /**
     * Execution order; a smaller weight runs earlier.
     *
     * @return handler priority
     */
    EventPriority priority() default EventPriority.NORMAL;

    /**
     * When {@code true}, the handler is skipped for an event already {@link
     * Cancellable#isCancelled() cancelled}; cancelled events are still delivered by default.
     *
     * @return whether to skip cancelled events
     */
    boolean notCallIfCancelled() default false;

    /**
     * When {@code true}, the handler is skipped once the chain has been {@link Event#prevent()
     * prevented}; this defaults to {@code true}. Opting in permits observation of a prevented
     * event, but {@link Event#setPrevent(boolean)} cannot clear its veto.
     *
     * @return whether to skip prevented events
     */
    boolean notCallIfPrevented() default true;
}
