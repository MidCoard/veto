/**
 * Portable event contracts for plugin extension.
 *
 * <p>An event carries a mutable payload that priority-ordered handlers transform in place; the
 * submitter reads the result after dispatch returns. {@link Event#prevent()} irreversibly marks the
 * event and skips later handlers by default; opt-in observers cannot clear that mark. Prevention
 * controls propagation only. Reversible producer-action cancellation is independently opt-in
 * through {@link CancellableEvent} or {@link Cancellable}; the producer reads its final flag after
 * delivery. Neither propagation nor action cancellation can relax a separate monotonic host
 * security decision. {@link Event#cancellation()} supplies a distinct read-only host stop signal.
 * The host invokes each contributed {@link Listener} under its plugin's lifecycle admission,
 * resolving recipients from the submitting thread's host session context. Ordinary listener
 * failures are contained and logged; host cancellation and fatal JVM failures remain distinct.
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
