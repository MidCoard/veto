package top.focess.veto.plugin.runtime;

import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.contract.PluginFailure;

/**
 * Concurrent invocation admission for one plugin activation.
 *
 * <p>This is the multi-threaded admission half of the plugin runtime: it counts admitted calls and
 * guards same-thread reentrancy, but owns no lifecycle state and holds no back-reference to its
 * owner. The lifecycle-dependent pieces (the current state and the slot-release handoff) are passed
 * in per call, so the compound "still active, take a slot" decision is made under the shared
 * monitor that {@link PluginLifecycle} also uses for its {@code ACTIVE -> STOPPING/FAILED}
 * transition.
 *
 * <p>Releasing a slot runs the decrement and the close-completion re-check on the lifecycle's
 * single control executor, never on the caller thread, which is what lets {@link
 * PluginLifecycle#close()} block until every admitted call has drained.
 *
 * <p>The supplied state reader runs under the shared monitor and must be a nonblocking state
 * lookup. Operation bodies run outside it and may run concurrently; this class does not serialize
 * plugin implementation state. Same-thread nesting within one activation reuses its slot, while
 * cross-activation nesting takes independent slots. Count access and decrement require the caller
 * to hold the shared monitor. A release handoff waits for the serial lifecycle executor.
 */
final class InvocationAdmission {
    /** Slot-release handoff; may fail when the control executor is already shut down. */
    @FunctionalInterface
    interface Release {
        void run() throws PluginFailure;
    }

    // Shared with PluginLifecycle: guards activeCalls together with the lifecycle state transition.
    private final @NonNull Object lock;
    // Marks "this thread is already inside this activation's admitted call" for nested reuse.
    private final @NonNull ThreadLocal<@Nullable Boolean> invoking =
            ThreadLocal.withInitial(() -> false);
    // Marks "this thread is inside some plugin invocation"; shared so lifecycle ops can refuse it.
    private static final @NonNull ThreadLocal<@Nullable Boolean> inInvocation =
            ThreadLocal.withInitial(() -> false);
    private int activeCalls;

    InvocationAdmission(@NonNull Object lock) {
        this.lock = lock;
    }

    /** Whether this thread is currently inside any plugin invocation. */
    static boolean inInvocation() {
        return Boolean.TRUE.equals(inInvocation.get());
    }

    /** Current admitted-call count; callers must hold the shared monitor. */
    int activeCalls() {
        return activeCalls;
    }

    /** Drops one admitted-call slot; callers must hold the shared monitor. */
    void decrement() {
        activeCalls--;
    }

    /**
     * Runs an admitted operation, taking a slot only while the lifecycle still permits it.
     *
     * <p>A same-thread nested call reuses the surrounding admission instead of taking a new slot.
     * The slot is released through {@code release}, which runs on the control executor so that
     * close-completion is re-checked there.
     *
     * @param operation the admitted work
     * @param state reads the owner's current lifecycle state
     * @param release releases this slot and re-checks close completion on the control executor
     */
    @SuppressWarnings("NullableProblems") // the @NonNull bound is required by the NullnessChecker
    <T extends @NonNull Object> @NonNull T execute(
            PluginLifecycle.@NonNull Operation<T> operation,
            @NonNull Supplier<@NonNull PluginState> state,
            @NonNull Release release)
            throws PluginFailure {
        if (PluginLifecycle.onControlThread())
            throw new PluginFailure(PluginFailure.Code.NOT_READY);
        // A same-thread nested adapter belongs to the already admitted invocation.
        if (Boolean.TRUE.equals(invoking.get())) return operation.run();
        synchronized (lock) {
            if (state.get() != PluginState.ACTIVE)
                throw new PluginFailure(PluginFailure.Code.NOT_READY);
            activeCalls++;
        }
        boolean nestedInvocation = Boolean.TRUE.equals(inInvocation.get());
        inInvocation.set(true);
        invoking.set(true);
        T result;
        try {
            result = operation.run();
        } finally {
            invoking.remove();
            if (nestedInvocation) inInvocation.set(true);
            else inInvocation.remove();
            // The executor remains alive until every admitted call has released its slot.
            release.run();
        }
        if (state.get() == PluginState.FAILED)
            throw new PluginFailure(PluginFailure.Code.NOT_READY);
        return result;
    }
}
