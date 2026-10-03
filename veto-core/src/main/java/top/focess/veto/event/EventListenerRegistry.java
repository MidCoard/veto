package top.focess.veto.event;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.api.event.AfterModelEvent;
import top.focess.veto.api.event.AfterToolEvent;
import top.focess.veto.api.event.AgentTerminatedEvent;
import top.focess.veto.api.event.BeforeInputEvent;
import top.focess.veto.api.event.BeforeModelEvent;
import top.focess.veto.api.event.BeforeObservationEvent;
import top.focess.veto.api.event.BeforeTextCommitEvent;
import top.focess.veto.api.event.BeforeToolEvent;
import top.focess.veto.api.event.Cancellable;
import top.focess.veto.api.event.Event;
import top.focess.veto.api.event.EventHandler;
import top.focess.veto.api.event.EventPriority;
import top.focess.veto.api.event.Listener;
import top.focess.veto.api.event.ServiceDirectoryChangedEvent;
import top.focess.veto.api.event.SessionDeletedEvent;
import top.focess.veto.api.event.UserLoggedInEvent;
import top.focess.veto.api.event.UserLogoutEvent;
import top.focess.veto.api.event.UserRegisteredEvent;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.contract.PluginFailure;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contribution.ContributionCatalog;
import top.focess.veto.api.plugin.contribution.ContributionEntry;
import top.focess.veto.plugin.runtime.PluginLifecycle;
import top.focess.veto.util.Nullness;

/**
 * Immutable dispatch table of compiled event handlers.
 *
 * <p>Public {@code @EventHandler} methods on a contributed {@link Listener} are validated and
 * compiled at registration into reusable method handles. Publication binds each handle to its
 * receiver and exact lifecycle activation. Dispatch invokes that bound handle with no
 * per-invocation reflection, synchronously on the producer's calling thread. Handlers fire in
 * {@link EventPriority} order within each event type, registration order as the stable tiebreak.
 * The host expands inherited handlers into each known concrete event's list at table construction,
 * most specific type first. A handler is skipped once the event is {@link Event#isPrevent()
 * prevented}, according to its annotation flag. Cancelled events are delivered unless a handler
 * opts out through its annotation. Every handler checks its bound owner's current active state and
 * runs under that owner's atomic admission. Ordinary listener failures are logged and contained;
 * host cooperative cancellation and fatal VM errors propagate.
 *
 * <p>The table is immutable and can serve concurrent dispatches, but it supplies no lock around
 * listener instances. One event's handlers run serially; distinct events can reach the same
 * listener concurrently. Producers own each mutable event for the duration of one dispatch.
 */
public final class EventListenerRegistry {
    private static final @NonNull List<Class<? extends Event>> HOST_EVENTS =
            List.of(
                    BeforeInputEvent.class,
                    BeforeModelEvent.class,
                    BeforeToolEvent.class,
                    BeforeObservationEvent.class,
                    BeforeTextCommitEvent.class,
                    AfterModelEvent.class,
                    AfterToolEvent.class,
                    UserRegisteredEvent.class,
                    UserLoggedInEvent.class,
                    UserLogoutEvent.class,
                    SessionDeletedEvent.class,
                    AgentTerminatedEvent.class,
                    ServiceDirectoryChangedEvent.class);
    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.event.EventListenerRegistry");

    private final @NonNull Map<Class<?>, List<RegisteredHandler>> byEventType;

    /** Host-owned preparation cache; ClassValue does not retain unloaded listener classes. */
    public static final class Preparation {
        private final @NonNull ClassValue<@NonNull List<Compiled>> handlers =
                new ClassValue<>() {
                    @Override
                    protected @NonNull List<Compiled> computeValue(@NonNull Class<?> type) {
                        return List.copyOf(compile(type));
                    }
                };

        /** Checks and compiles a listener before its registration is stored or published. */
        public void prepare(@NonNull Listener listener) {
            log.debug("Prepared {} listener handlers", compiled(listener).size());
        }

        private @NonNull List<Compiled> compiled(@NonNull Listener listener) {
            return handlers.get(listener.getClass());
        }
    }

    private EventListenerRegistry(@NonNull Map<Class<?>, List<RegisteredHandler>> byEventType) {
        this.byEventType = byEventType;
    }

    /** Whether this concrete event has prepared handlers; unknown event types are rejected. */
    public boolean hasHandlers(@NonNull Event event) {
        var handlers = byEventType.get(event.getClass());
        if (handlers == null) throw new IllegalArgumentException("Unregistered event type");
        return !handlers.isEmpty();
    }

    /** Builds concrete routes bound to their exact contributing lifecycle and listener instance. */
    public static @NonNull EventListenerRegistry build(
            @NonNull ContributionCatalog catalog,
            @NonNull Map<@NonNull String, @NonNull PluginLifecycle> owners,
            @NonNull Preparation preparation) {
        Map<Class<?>, List<RegisteredHandler>> grouped = new HashMap<>();
        var listeners = catalog.entries(StandardContributionPoints.LISTENERS);
        for (ContributionEntry<Listener> entry : listeners) {
            Listener listener = entry.implementation();
            String namespace = entry.source().namespace();
            PluginLifecycle owner = owners.get(namespace);
            if (owner == null) throw new IllegalArgumentException("Listener activation is missing");
            List<Compiled> compiled = preparation.compiled(listener);
            for (Compiled handler : compiled) {
                RegisteredHandler registered =
                        new RegisteredHandler(
                                namespace,
                                owner,
                                handler.handle().bindTo(listener),
                                handler.weight(),
                                handler.notCallIfPrevented(),
                                handler.notCallIfCancelled());
                grouped.computeIfAbsent(handler.eventType(), ignored -> new ArrayList<>())
                        .add(registered);
            }
        }
        for (var group : grouped.values())
            group.sort(Comparator.comparingInt(RegisteredHandler::weight));
        Map<Class<?>, List<RegisteredHandler>> frozen = new HashMap<>();
        for (Class<? extends Event> concrete : HOST_EVENTS) {
            List<RegisteredHandler> dispatch = new ArrayList<>();
            for (Class<?> type = concrete;
                    type != null && Event.class.isAssignableFrom(type);
                    type = type.getSuperclass()) {
                List<RegisteredHandler> group = grouped.get(type);
                if (group != null) dispatch.addAll(group);
            }
            frozen.put(concrete, List.copyOf(dispatch));
        }
        return new EventListenerRegistry(Map.copyOf(frozen));
    }

    /** Submits an event to all active owners. */
    public void submit(@NonNull Event event) {
        submit(event, null);
    }

    /**
     * Executes the prepared event route synchronously under each active owner's admission. A null
     * selection means all active plugins; an explicit set restricts delivery to those identities.
     * Ordinary handler and admission failures are logged and contained. Host cooperative
     * cancellation is checked before each eligible handler and propagates independently, as do
     * fatal VM errors and thread death.
     *
     * @param event producer-owned event
     * @param selected selected plugin identities, or null for all active plugins
     */
    @SuppressWarnings("removal") // ThreadDeath remains a fatal callback signal while supported.
    public void submit(@NonNull Event event, Set<String> selected) {
        List<RegisteredHandler> handlers = byEventType.get(event.getClass());
        if (handlers == null) throw new IllegalArgumentException("Unregistered event type");
        for (RegisteredHandler handler : handlers) {
            if (selected != null && !selected.contains(handler.namespace())) continue;
            if (event.isPrevent() && handler.notCallIfPrevented()) continue;
            if (handler.notCallIfCancelled()
                    && event instanceof Cancellable cancellable
                    && cancellable.isCancelled()) continue;
            if (handler.owner().state() != PluginState.ACTIVE) continue;
            var cancellation = event.cancellation();
            if (cancellation != null && cancellation.isCancelled())
                throw new CancellationException("Event delivery cancelled");
            try {
                handler.invoke(event);
            } catch (VirtualMachineError | ThreadDeath fatal) {
                throw fatal;
            } catch (Throwable failure) {
                log.warn("Event listener {} failed", handler.namespace(), failure);
            }
        }
    }

    private record Compiled(
            @NonNull Class<?> eventType,
            @NonNull MethodHandle handle,
            int weight,
            boolean notCallIfPrevented,
            boolean notCallIfCancelled) {}

    private static @NonNull List<Compiled> compile(@NonNull Class<?> type) {
        List<Compiled> result = new ArrayList<>();
        for (Method method : type.getMethods()) {
            if (method.isSynthetic() || method.isBridge()) continue;
            EventHandler annotation = method.getAnnotation(EventHandler.class);
            if (annotation == null) continue;
            if (Modifier.isStatic(method.getModifiers()))
                throw new IllegalArgumentException("@EventHandler must be an instance method");
            if (method.getParameterCount() != 1)
                throw new IllegalArgumentException("@EventHandler takes exactly one parameter");
            Class<?> eventType = method.getParameterTypes()[0];
            if (!Event.class.isAssignableFrom(eventType))
                throw new IllegalArgumentException("@EventHandler parameter must be an Event");
            if (HOST_EVENTS.stream().noneMatch(eventType::isAssignableFrom))
                throw new IllegalArgumentException("@EventHandler has no host event route");
            if (method.getReturnType() != void.class)
                throw new IllegalArgumentException("@EventHandler must return void");
            result.add(
                    new Compiled(
                            eventType,
                            compileInvoker(method),
                            Nullness.requireNonNull(annotation.priority()).weight(),
                            annotation.notCallIfPrevented(),
                            annotation.notCallIfCancelled()));
        }
        return result;
    }

    @SuppressWarnings("removal") // ThreadDeath remains a fatal preparation signal while supported.
    private static @NonNull MethodHandle compileInvoker(@NonNull Method method) {
        Class<?> declaring = method.getDeclaringClass();
        try {
            MethodHandles.Lookup lookup;
            try {
                lookup = MethodHandles.privateLookupIn(declaring, MethodHandles.lookup());
            } catch (IllegalAccessException inaccessible) {
                method.setAccessible(true);
                lookup = MethodHandles.lookup();
            }
            return lookup.unreflect(method);
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable failure) {
            throw new IllegalArgumentException("Cannot compile event handler " + method, failure);
        }
    }

    private record RegisteredHandler(
            @NonNull String namespace,
            @NonNull PluginLifecycle owner,
            @NonNull MethodHandle handle,
            int weight,
            boolean notCallIfPrevented,
            boolean notCallIfCancelled) {

        private void invoke(@NonNull Event event) throws PluginFailure {
            owner.execute(
                    () -> {
                        try {
                            handle.invoke(event);
                        } catch (PluginFailure | RuntimeException | Error failure) {
                            throw failure;
                        } catch (Throwable failure) {
                            throw new PluginFailure(PluginFailure.Code.INTERNAL_FAILURE);
                        }
                        return Boolean.TRUE;
                    });
        }
    }
}
