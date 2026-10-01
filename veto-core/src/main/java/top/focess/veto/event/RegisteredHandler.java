package top.focess.veto.event;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.event.EventPriority;
import top.focess.veto.api.event.Listener;

/**
 * One compiled handler bound to its contributing plugin. Immutable after registration; dispatch
 * reads these fields without further reflection.
 *
 * @param namespace contributing plugin identity used for selection and admission
 * @param listener object declaring the handler
 * @param invoker compiled zero-reflection call
 * @param weight {@link EventPriority} sort weight; smaller runs earlier
 * @param order registration sequence, the stable tiebreak within one weight
 * @param notCallIfPrevented skip once the chain is prevented
 * @param notCallIfCancelled skip once the event is cancelled
 */
public record RegisteredHandler(
        @NonNull String namespace,
        @NonNull Listener listener,
        @NonNull EventInvoker invoker,
        int weight,
        int order,
        boolean notCallIfPrevented,
        boolean notCallIfCancelled)
        implements Comparable<RegisteredHandler> {

    @Override
    public int compareTo(@NonNull RegisteredHandler other) {
        int byWeight = Integer.compare(this.weight, other.weight);
        return byWeight != 0 ? byWeight : Integer.compare(this.order, other.order);
    }
}
