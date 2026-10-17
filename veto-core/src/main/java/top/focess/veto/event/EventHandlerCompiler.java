package top.focess.veto.event;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.api.event.Event;
import top.focess.veto.api.event.EventHandler;
import top.focess.veto.api.event.Listener;
import top.focess.veto.util.Nullness;

/** Thread-safe class-unloading-safe cache of validated event method handles. */
public final class EventHandlerCompiler {
    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.event.EventHandlerCompiler");
    private final @NonNull ClassValue<@NonNull List<CompiledEventHandler>> handlers =
            new ClassValue<>() {
                @Override
                protected @NonNull List<CompiledEventHandler> computeValue(@NonNull Class<?> type) {
                    return List.copyOf(compile(type));
                }
            };

    /** Checks and compiles a listener before its registration is stored or published. */
    public void prepare(@NonNull Listener listener) {
        log.debug("Prepared {} listener handlers", compiled(listener).size());
    }

    @NonNull List<CompiledEventHandler> compiled(@NonNull Listener listener) {
        return handlers.get(listener.getClass());
    }

    private static @NonNull List<CompiledEventHandler> compile(@NonNull Class<?> type) {
        List<CompiledEventHandler> result = new ArrayList<>();
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
            if (EventListenerRegistry.HOST_EVENTS.stream().noneMatch(eventType::isAssignableFrom))
                throw new IllegalArgumentException("@EventHandler has no host event route");
            if (method.getReturnType() != void.class)
                throw new IllegalArgumentException("@EventHandler must return void");
            result.add(
                    new CompiledEventHandler(
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

    /** Immutable unbound event method and its dispatch policy. */
    record CompiledEventHandler(
            @NonNull Class<?> eventType,
            @NonNull MethodHandle handle,
            int weight,
            boolean notCallIfPrevented,
            boolean notCallIfCancelled) {}
}
