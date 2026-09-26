package top.focess.veto.event;

import java.lang.invoke.CallSite;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.event.Cancellable;
import top.focess.veto.api.event.Event;
import top.focess.veto.api.event.EventHandler;
import top.focess.veto.api.event.Listener;
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
 * once, at registration, and compiled through {@link LambdaMetafactory} into an {@link
 * EventInvoker}. Dispatch is then a plain interface call with no per-invocation reflection, run
 * synchronously on the calling (workflow) thread. Handlers fire in {@link
 * top.focess.veto.api.event.EventPriority} order, registration order as the stable tiebreak,
 * walking the event's supertype chain from most specific to least. A handler is skipped once the
 * chain is {@link Event#isPrevent() prevented} or, for a {@link Cancellable} event, cancelled,
 * according to its annotation flags; only plugins selected for the session are invoked, each under
 * its own admission.
 */
public final class EventListenerRegistry {
    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.event.EventListenerRegistry");

    private final @NonNull Map<Class<?>, List<RegisteredHandler>> byEventType;
    private final @NonNull PluginExecutor executor;

    private EventListenerRegistry(
            @NonNull Map<Class<?>, List<RegisteredHandler>> byEventType,
            @NonNull PluginExecutor executor) {
        this.byEventType = byEventType;
        this.executor = executor;
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
        Map<Class<?>, List<RegisteredHandler>> grouped = new HashMap<>();
        Map<Class<?>, List<Compiled>> cache = new HashMap<>();
        int order = 0;
        for (ContributionEntry<Listener> entry :
                catalog.entries(StandardContributionPoints.LISTENERS)) {
            Listener listener = entry.implementation();
            String namespace = entry.source().namespace();
            List<Compiled> compiled =
                    cache.computeIfAbsent(listener.getClass(), EventListenerRegistry::compile);
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
        for (var group : grouped.entrySet()) {
            List<RegisteredHandler> sorted = new ArrayList<>(group.getValue());
            sorted.sort(Comparator.naturalOrder());
            frozen.put(group.getKey(), List.copyOf(sorted));
        }
        return new EventListenerRegistry(Map.copyOf(frozen), executor);
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
                selected,
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
        dispatch(
                event,
                active,
                (namespace, failure) ->
                        log.warn("Lifecycle listener {} failed", namespace, failure));
    }

    private void dispatch(
            @NonNull Event event,
            @NonNull Set<String> selected,
            @NonNull FailureHandler onFailure) {
        for (Class<?> type = event.getClass();
                type != null && ToolDocs.nonNullClass(Event.class).isAssignableFrom(type);
                type = type.getSuperclass()) {
            List<RegisteredHandler> handlers = byEventType.get(type);
            if (handlers == null) continue;
            for (RegisteredHandler handler : handlers) {
                if (event.isPrevent() && handler.notCallIfPrevented()) continue;
                if (event instanceof Cancellable cancellable
                        && cancellable.isCancelled()
                        && handler.notCallIfCancelled()) continue;
                if (!selected.contains(handler.namespace())) continue;
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
            EventHandler annotation =
                    method.getAnnotation(ToolDocs.nonNullClass(EventHandler.class));
            if (annotation == null) continue;
            if (method.getParameterCount() != 1)
                throw new IllegalArgumentException("@EventHandler takes exactly one parameter");
            Class<?> eventType = method.getParameterTypes()[0];
            if (!ToolDocs.nonNullClass(Event.class).isAssignableFrom(eventType))
                throw new IllegalArgumentException("@EventHandler parameter must be an Event");
            if (method.getReturnType() != void.class)
                throw new IllegalArgumentException("@EventHandler must return void");
            result.add(
                    new Compiled(
                            eventType,
                            compileInvoker(method, eventType),
                            Nullness.requireNonNull(annotation.priority()).weight(),
                            annotation.notCallIfPrevented(),
                            annotation.notCallIfCancelled()));
        }
        return result;
    }

    private static @NonNull EventInvoker compileInvoker(
            @NonNull Method method, @NonNull Class<?> eventType) {
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
            CallSite site =
                    LambdaMetafactory.metafactory(
                            lookup,
                            "invoke",
                            MethodType.methodType(ToolDocs.nonNullClass(EventInvoker.class)),
                            MethodType.methodType(
                                    void.class,
                                    ToolDocs.nonNullClass(Listener.class),
                                    ToolDocs.nonNullClass(Event.class)),
                            handle,
                            MethodType.methodType(void.class, declaring, eventType));
            return (EventInvoker) site.getTarget().invoke();
        } catch (Throwable failure) {
            throw new IllegalArgumentException("Cannot compile event handler " + method, failure);
        }
    }
}
