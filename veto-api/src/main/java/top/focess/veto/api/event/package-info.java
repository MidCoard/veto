/**
 * Portable event contracts for plugin extension.
 *
 * <p>An event carries a mutable payload that priority-ordered handlers transform in place; the
 * submitter reads the result after dispatch returns. {@link
 * top.focess.veto.api.event.Event#prevent()} irreversibly marks the event and skips later handlers
 * by default; opt-in observers cannot clear that mark. It backs the host's monotonic security
 * decisions. Every event is preventable through that base, but reversible cancellation is opt-in:
 * an event that needs it extends {@link top.focess.veto.api.event.CancellableEvent} (or implements
 * {@link top.focess.veto.api.event.Cancellable} directly), and such cancellation is reserved for
 * non-security notifications. The host registers each {@link top.focess.veto.api.event.Listener}
 * contributed by a plugin and invokes its handlers under that plugin's lifecycle admission and the
 * session's selection.
 */
@DefaultQualifier(
        value = Nullable.class,
        locations = {
            TypeUseLocation.FIELD,
            TypeUseLocation.PARAMETER,
            TypeUseLocation.RETURN,
            TypeUseLocation.UPPER_BOUND
        })
package top.focess.veto.api.event;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.checkerframework.framework.qual.TypeUseLocation;
