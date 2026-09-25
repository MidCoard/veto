package top.focess.veto.plugin.runtime;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.veto.api.agent.workflow.PluginAwait;
import top.focess.veto.api.plugin.*;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.PluginFailure;

/** Host-owned lifecycle callbacks, with atomic admission and cleanup on the control executor. */
public final class ManagedPlugin implements AutoCloseable {
    private final @NonNull VetoPlugin plugin;
    // Published for diagnostics and cooperative cancellation; never used to admit a call.
    private volatile @NonNull PluginState state = PluginState.NEW;
    private final @NonNull ExecutorService lifecycle;
    private static final @NonNull ThreadLocal<@Nullable Boolean> controlling =
            ThreadLocal.withInitial(() -> false);
    private final @NonNull ThreadLocal<@Nullable Boolean> invoking =
            ThreadLocal.withInitial(() -> false);
    private static final @NonNull ThreadLocal<@Nullable Boolean> inInvocation =
            ThreadLocal.withInitial(() -> false);
    private final @NonNull CompletableFuture<Void> active = new CompletableFuture<>();
    private final @NonNull CompletableFuture<Void> closed = new CompletableFuture<>();
    // Cleanup is owned by the lifecycle executor; admission/count changes use this monitor.
    private boolean cleaned;
    private boolean stopping;
    private int activeCalls;
    private final @NonNull String activationId = java.util.UUID.randomUUID().toString();
    private final @NonNull Map<PluginAwait, PluginAwait> waits = new ConcurrentHashMap<>();

    private final @NonNull Map<Object, Runnable> resources = new ConcurrentHashMap<>();

    private final @NonNull Map<Object, Runnable> stoppingResources = new ConcurrentHashMap<>();

    /** Host cancellation signals that must run before waiting for admitted calls to drain. */
    public synchronized void ownStoppingResource(
            @NonNull Object identity, @NonNull Runnable release) {
        if (state != PluginState.ACTIVE) throw new IllegalStateException("Plugin is not active");
        stoppingResources.putIfAbsent(identity, release);
    }

    /** Runs the callback under admission once the plugin reaches ACTIVE; revocation cancels it. */
    public void whenActive(@NonNull Runnable callback) {
        active.thenRunAsync(
                () -> {
                    try {
                        execute(
                                () -> {
                                    callback.run();
                                    return true;
                                });
                    } catch (PluginFailure stopped) {
                        /* Lifecycle revocation cancels the callback. */
                    }
                });
    }

    /** Host resources are closed even when the plugin's own shutdown callback fails. */
    public synchronized void ownResource(@NonNull Runnable release) {
        if (state != PluginState.ACTIVE) throw new IllegalStateException("Plugin is not active");
        resources.putIfAbsent(release, release);
    }

    /** Registers a host resource under an identity; returns {@code false} if already present. */
    public synchronized boolean ownResource(@NonNull Object identity, @NonNull Runnable release) {
        if (state != PluginState.ACTIVE) throw new IllegalStateException("Plugin is not active");
        return resources.putIfAbsent(identity, release) == null;
    }

    /** Drops a previously owned resource so its release callback no longer runs on cleanup. */
    public void releaseResource(@NonNull Object identity) {
        resources.remove(identity);
        stoppingResources.remove(identity);
    }

    /** Only the lifecycle callback may use a revoked handle for its final cleanup. */
    public boolean cleaningResources() {
        return (state == PluginState.STOPPING || state == PluginState.FAILED)
                && Boolean.TRUE.equals(controlling.get());
    }

    /** Waiting does not retain an invocation admission; stopping fails it immediately. */
    public @NonNull PluginAwait ownAwait(@NonNull PluginAwait signal) {
        CompletableFuture<Boolean> ready = new CompletableFuture<>();
        var owned = new PluginAwait(bindingId() + "/" + signal.token(), ready);
        synchronized (this) {
            if (state != PluginState.ACTIVE)
                throw new IllegalStateException("Plugin is not active");
            var existing = waits.get(signal);
            if (existing != null) return existing;
            waits.put(signal, owned);
        }
        signal.ready()
                .whenComplete(
                        (value, failure) -> {
                            if (failure != null) ready.completeExceptionally(failure);
                            else ready.complete(Boolean.TRUE.equals(value));
                        });
        ready.whenComplete(
                (value, failure) -> {
                    waits.remove(signal, owned);
                    if (!signal.ready().isDone()) signal.ready().cancel(false);
                });
        return owned;
    }

    private void stopWaits() {
        for (var signal : waits.values())
            signal.ready()
                    .completeExceptionally(
                            new IllegalStateException("Plugin stopped while awaiting work"));
    }

    /** Stable per-activation identity used to namespace awaits and work continuations. */
    public @NonNull String bindingId() {
        return "plugin:" + identity().id() + ":" + identity().version() + ":" + activationId;
    }

    /** Wraps an implementation whose lifecycle transitions will run on the given executor. */
    public ManagedPlugin(@NonNull VetoPlugin plugin, @NonNull ExecutorService lifecycle) {
        this.plugin = plugin;
        this.lifecycle = lifecycle;
    }

    public @NonNull VetoPlugin implementation() {
        return plugin;
    }

    public @NonNull PluginIdentity identity() {
        return plugin.identity();
    }

    public @NonNull PluginState state() {
        return state;
    }

    /** A unit of plugin work admitted only while the plugin lifecycle permits it. */
    @FunctionalInterface
    @SuppressWarnings("NullableProblems") // the @NonNull bound is required by the NullnessChecker
    public interface Operation<T extends @NonNull Object> {
        /** Performs the admitted work, or throws {@link PluginFailure} if it cannot complete. */
        @NonNull T run() throws PluginFailure;
    }

    /** Initializes the plugin on the control executor, returning its declared contributions. */
    public @NonNull PluginContributions initialize(
            @NonNull PluginContext context, JsonValue.@NonNull ObjectValue configuration)
            throws PluginFailure {
        requireExternalControl();
        return await(
                submit(
                        () -> {
                            require(PluginState.NEW);
                            state = PluginState.INITIALIZING;
                            try {
                                if (!identity().equals(context.identity()))
                                    throw new PluginFailure(
                                            PluginFailure.Code.INVALID_CONFIGURATION);
                                var contributions =
                                        plugin.initialize(
                                                new PluginContext(
                                                        context.identity(),
                                                        this::fail,
                                                        this::state,
                                                        context.hostServices()),
                                                configuration);
                                state = PluginState.INITIALIZED;
                                return contributions;
                            } catch (Throwable failure) {
                                failOnControlThread();
                                throw safe(failure);
                            }
                        }));
    }

    /** Transitions an initialized plugin to ACTIVE on the control executor. */
    public void start() throws PluginFailure {
        requireExternalControl();
        await(
                submit(
                        () -> {
                            require(PluginState.INITIALIZED);
                            state = PluginState.STARTING;
                            try {
                                plugin.start();
                                state = PluginState.ACTIVE;
                                active.complete(null);
                            } catch (Throwable failure) {
                                failOnControlThread();
                                throw safe(failure);
                            }
                            return true;
                        }));
    }

    /** Admission checks the stop state atomically; handlers run outside the control thread. */
    @SuppressWarnings("NullableProblems") // the @NonNull bound is required by the NullnessChecker
    public <T extends @NonNull Object> @NonNull T execute(@NonNull Operation<T> operation)
            throws PluginFailure {
        if (Boolean.TRUE.equals(controlling.get()))
            throw new PluginFailure(PluginFailure.Code.NOT_READY);
        // A same-thread nested adapter belongs to the already admitted invocation.
        if (Boolean.TRUE.equals(invoking.get())) return operation.run();
        synchronized (this) {
            require(PluginState.ACTIVE);
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
            await(
                    submit(
                            () -> {
                                synchronized (this) {
                                    activeCalls--;
                                }
                                finishClose();
                                return true;
                            }));
        }
        if (state == PluginState.FAILED) throw new PluginFailure(PluginFailure.Code.NOT_READY);
        return result;
    }

    @Override
    public void close() {
        requireExternalControl();
        if (closed.isDone()) return;
        submit(
                () -> {
                    synchronized (this) {
                        if (state != PluginState.CLOSED && state != PluginState.FAILED)
                            state = PluginState.STOPPING;
                    }
                    signalStopping();
                    finishClose();
                    return true;
                });
        // Do not block the control thread: completions must still be able to release their slots.
        closed.join();
    }

    /** Failure callbacks only report an event; cleanup always runs on the control thread. */
    private void fail() {
        submit(
                () -> {
                    failOnControlThread();
                    return true;
                });
    }

    private void failOnControlThread() {
        synchronized (this) {
            if (state == PluginState.CLOSED || state == PluginState.FAILED) return;
            state = PluginState.FAILED;
        }
        // Failure aborts owned resources to unblock outstanding I/O; graceful close drains first.
        signalStopping();
        cleanup();
        finishClose();
    }

    private void finishClose() {
        synchronized (this) {
            if (activeCalls != 0 || (state != PluginState.STOPPING && state != PluginState.FAILED))
                return;
        }
        cleanup();
        if (state != PluginState.FAILED) state = PluginState.CLOSED;
        closed.complete(null);
    }

    private void signalStopping() {
        if (stopping) return;
        stopping = true;
        for (var release : stoppingResources.values()) {
            try {
                release.run();
            } catch (Throwable failure) {
                state = PluginState.FAILED;
            }
        }
        stoppingResources.clear();
        try {
            plugin.stopping();
        } catch (Throwable failure) {
            state = PluginState.FAILED;
        }
        if (state == PluginState.FAILED) cleanup();
    }

    private void cleanup() {
        if (cleaned) return;
        cleaned = true;
        active.completeExceptionally(new IllegalStateException("Plugin stopped before activation"));
        stopWaits();
        try {
            plugin.close();
        } catch (Throwable failure) {
            state = PluginState.FAILED;
        } finally {
            for (var release : resources.values()) {
                try {
                    release.run();
                } catch (Throwable failure) {
                    state = PluginState.FAILED;
                }
            }
            resources.clear();
        }
    }

    private void require(@NonNull PluginState expected) throws PluginFailure {
        if (state != expected) throw new PluginFailure(PluginFailure.Code.NOT_READY);
    }

    private void requireExternalControl() {
        if (Boolean.TRUE.equals(controlling.get()) || Boolean.TRUE.equals(inInvocation.get()))
            throw new IllegalStateException(
                    "Lifecycle operations cannot wait from a plugin callback");
    }

    private <T extends @NonNull Object> @NonNull CompletableFuture<T> submit(
            @NonNull Operation<T> operation) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            lifecycle.execute(
                    () -> {
                        controlling.set(true);
                        try {
                            result.complete(operation.run());
                        } catch (Throwable failure) {
                            result.completeExceptionally(failure);
                        } finally {
                            controlling.remove();
                        }
                    });
        } catch (RejectedExecutionException closedExecutor) {
            result.completeExceptionally(new PluginFailure(PluginFailure.Code.NOT_READY));
        }
        return result;
    }

    @SuppressWarnings("NullableProblems") // the @NonNull bound is required by the NullnessChecker
    private static <T extends @NonNull Object> @NonNull T await(
            @NonNull CompletableFuture<T> result) throws PluginFailure {
        try {
            return result.join();
        } catch (CompletionException failure) {
            if (failure.getCause() instanceof PluginFailure declared) throw declared;
            throw new PluginFailure(PluginFailure.Code.INTERNAL_FAILURE);
        }
    }

    private static @NonNull PluginFailure safe(@NonNull Throwable failure) {
        return failure instanceof PluginFailure declared
                ? declared
                : new PluginFailure(PluginFailure.Code.INTERNAL_FAILURE);
    }
}
