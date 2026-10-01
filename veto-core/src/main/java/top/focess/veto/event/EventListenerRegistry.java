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
import java.util.function.Predicate;
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
import top.focess.veto.api.event.WorkflowEvent;
import top.focess.veto.api.plugin.contract.PluginFailure;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contribution.ContributionCatalog;
import top.focess.veto.api.plugin.contribution.ContributionEntry;
import top.focess.veto.util.Nullness;

/**
 * Immutable dispatch table of compiled event handlers.
 *
 * <p>Every {@code @EventHandler} method on a contributed {@link Listener} is reflected exactly
 * once, at registration, into an {@link EventInvoker}. Dispatch is then a plain interface call
 * backed by a method handle, with no per-invocation reflection, run synchronously on the calling
 * (workflow) thread. Handlers fire in {@link EventPriority} order within each event type,
 * registration order as the stable tiebreak. The host expands inherited handlers into each known
 * concrete event's list at table construction, most specific type first. A handler is skipped once
 * the event is {@link Event#isPrevent() prevented} or, for a {@link Cancellable} event, cancelled,
 * according to its annotation flags; only plugins selected for the session are invoked, each under
 * its own admission.
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
    private final @NonNull PluginExecutor executor;
    private final @NonNull Predicate<@NonNull String> active;

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

    private EventListenerRegistry(
            @NonNull Map<Class<?>, List<RegisteredHandler>> byEventType,
            @NonNull PluginExecutor executor,
            @NonNull Predicate<@NonNull String> active) {
        this.byEventType = byEventType;
        this.executor = executor;
        this.active = active;
    }

    /**
     * Compiles every listener contributed at {@link StandardContributionPoints#LISTENERS} once.
     *
     * @param catalog frozen contribution catalog
     * @param executor admission bridge to the contributing plugins
     * @return the immutable dispatch table
     */
    public static @NonNull EventListenerRegistry build(
            @NonNull ContributionCatalog catalog, @NonNull PluginExecutor executor) {
        return build(catalog, executor, new Preparation(), namespace -> true);
    }

    /** Builds prepared routes using the owning host's reusable registration-time compilation. */
    public static @NonNull EventListenerRegistry build(
            @NonNull ContributionCatalog catalog,
            @NonNull PluginExecutor executor,
            @NonNull Preparation preparation,
            @NonNull Predicate<@NonNull String> active) {
        Map<Class<?>, List<RegisteredHandler>> grouped = new HashMap<>();
        int order = 0;
        for (ContributionEntry<Listener> entry :
                catalog.entries(StandardContributionPoints.LISTENERS)) {
            Listener listener = entry.implementation();
            String namespace = entry.source().namespace();
            List<Compiled> compiled = preparation.compiled(listener);
            for (Compiled handler : compiled) {
                RegisteredHandler registered =
                        new RegisteredHandler(
                                namespace,
                                listener,
                                handler.invoker(),
                                handler.weight(),
                                order++,
                                handler.notCallIfPrevented(),
                                handler.notCallIfCancelled());
                grouped.computeIfAbsent(handler.eventType(), ignored -> new ArrayList<>())
                        .add(registered);
            }
        }
        Map<Class<?>, List<RegisteredHandler>> frozen = new HashMap<>();
        for (Class<? extends Event> concrete : HOST_EVENTS) {
            List<RegisteredHandler> dispatch = new ArrayList<>();
            for (Class<?> type = concrete;
                    type != null && Event.class.isAssignableFrom(type);
                    type = type.getSuperclass()) {
                List<RegisteredHandler> group = grouped.get(type);
                if (group != null) {
                    List<RegisteredHandler> sorted = new ArrayList<>(group);
                    sorted.sort(Comparator.naturalOrder());
                    dispatch.addAll(sorted);
                }
            }
            frozen.put(concrete, List.copyOf(dispatch));
        }
        return new EventListenerRegistry(Map.copyOf(frozen), executor, active);
    }

    /**
     * Dispatches a workflow event to every selected handler, most specific supertype first.
     *
     * <p>Fail-closed: a cancelled workflow or a failing handler aborts dispatch with a sanitized
     * {@link IllegalStateException}, because plugin messages may carry raw inputs that must never
     * reach conversation history.
     *
     * @param event the event to dispatch; handlers transform it in place
     * @param selected plugin identities selected for the current session
     */
    public void submit(@NonNull Event event, @NonNull Set<String> selected) {
        dispatch(
                event,
                selected::contains,
                (namespace, failure) -> {
                    throw new IllegalStateException("Workflow listener unavailable");
                });
    }

    /**
     * Broadcasts a best-effort lifecycle notification to every active listener.
     *
     * <p>Unlike {@link #submit}, a failing handler is logged and skipped so one plugin can never
     * break logout, session deletion, or agent termination. Lifecycle events are not {@link
     * WorkflowEvent}s, so the cancellation gate is inert here.
     *
     * @param event the lifecycle notification to broadcast
     * @param active plugin identities currently active
     */
    public void broadcast(@NonNull Event event, @NonNull Set<String> active) {
        broadcast(event, active::contains);
    }

    /** Broadcasts only to prepared route recipients whose current activation is active. */
    public void broadcast(@NonNull Event event) {
        broadcast(event, active);
    }

    private void broadcast(@NonNull Event event, @NonNull Predicate<@NonNull String> recipients) {
        dispatch(
                event,
                recipients,
                (namespace, failure) ->
                        log.warn("Lifecycle listener {} failed", namespace, failure));
    }

    private void dispatch(
            @NonNull Event event,
            @NonNull Predicate<@NonNull String> selected,
            @NonNull FailureHandler onFailure) {
        List<RegisteredHandler> handlers = byEventType.get(event.getClass());
        if (handlers == null) throw new IllegalArgumentException("Unregistered event type");
        for (RegisteredHandler handler : handlers) {
            if (event.isPrevent() && handler.notCallIfPrevented()) continue;
            if (event instanceof Cancellable cancellable
                    && cancellable.isCancelled()
                    && handler.notCallIfCancelled()) continue;
            if (!selected.test(handler.namespace())) continue;
            if (event instanceof WorkflowEvent workflow) {
                try {
                    workflow.cancellation().checkCancelled();
                } catch (PluginFailure cancelled) {
                    onFailure.onFailure(handler.namespace(), cancelled);
                    continue;
                }
            }
            try {
                executor.admit(
                        handler.namespace(),
                        () -> handler.invoker().invoke(handler.listener(), event));
            } catch (PluginFailure | RuntimeException failure) {
                onFailure.onFailure(handler.namespace(), failure);
            }
        }
    }

    /** Per-handler failure policy: abort the workflow, or log and continue a broadcast. */
    @FunctionalInterface
    private interface FailureHandler {
        void onFailure(@NonNull String namespace, @NonNull Exception failure);
    }

    private record Compiled(
            @NonNull Class<?> eventType,
            @NonNull EventInvoker invoker,
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

    private static @NonNull EventInvoker compileInvoker(@NonNull Method method) {
        Class<?> declaring = method.getDeclaringClass();
        try {
            MethodHandles.Lookup lookup;
            try {
                lookup = MethodHandles.privateLookupIn(declaring, MethodHandles.lookup());
            } catch (IllegalAccessException inaccessible) {
                method.setAccessible(true);
                lookup = MethodHandles.lookup();
            }
            MethodHandle handle = lookup.unreflect(method);
            return (listener, event) -> {
                try {
                    handle.invoke(listener, event);
                } catch (Exception exception) {
                    throw exception;
                } catch (Error error) {
                    throw error;
                } catch (Throwable failure) {
                    throw new IllegalStateException("Event handler failed", failure);
                }
            };
        } catch (Throwable failure) {
            throw new IllegalArgumentException("Cannot compile event handler " + method, failure);
        }
    }
}
