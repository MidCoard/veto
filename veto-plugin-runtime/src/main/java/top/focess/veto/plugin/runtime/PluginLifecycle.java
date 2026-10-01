package top.focess.veto.plugin.runtime;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.veto.api.agent.workflow.PluginAwait;
import top.focess.veto.api.plugin.*;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.PluginFailure;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contribution.Contribution;
import top.focess.veto.api.plugin.contribution.ContributionPoint;

/**
 * Host-owned controller for one {@link VetoPlugin} instance: construction, activation, admission,
 * and cleanup are serialized on the control executor. The plugin implements the callbacks; this
 * class decides when to invoke them. A normal path is {@link #construct(PluginContext,
 * JsonValue.ObjectValue)}, {@link #start()}, then {@link #close()}. Closing signals the plugin's
 * stopping callback, drains admitted calls, invokes its close callback, closes contributed
 * resources, and finally releases the installed package loader. Failure can begin cleanup before
 * admitted calls drain. A constructor failure has no plugin instance to call back but still closes
 * host-owned partial resources.
 *
 * <p>This is the single-threaded lifecycle half of the plugin runtime. The concurrent invocation
 * counting and same-thread reentrancy guard live in {@link InvocationAdmission}; the two share one
 * monitor ({@link #lock}) so the compound "still active, take a slot" decision is atomic against
 * the {@code ACTIVE -> STOPPING/FAILED} transition performed here. Releasing a slot is routed back
 * through {@link #submit} so the close-completion re-check runs on the control thread, which is
 * what lets {@link #close()} block until every admitted call has drained.
 *
 * <p>Threading: external callers may admit operations and register resources concurrently. The
 * shared monitor guards admission counts and resource registration against revocation; plugin
 * operations execute on their caller threads outside that monitor. Construction, start, stopping,
 * and cleanup callbacks execute on the supplied serial control executor, outside the admission
 * monitor. That executor must remain available until close completes. Lifecycle methods reject
 * synchronous waits from control callbacks or admitted operations to prevent self-deadlock.
 */
public final class PluginLifecycle implements AutoCloseable {
    private VetoPlugin plugin;
    private final InstalledPlugin installed;
    // Published for diagnostics and cooperative cancellation; never used to admit a call.
    private volatile @NonNull PluginState state = PluginState.NEW;
    private final @NonNull ExecutorService lifecycle;
    private static final @NonNull ThreadLocal<@Nullable Boolean> controlling =
            ThreadLocal.withInitial(() -> false);
    private final @NonNull CompletableFuture<Void> active = new CompletableFuture<>();
    private final @NonNull CompletableFuture<Void> closed = new CompletableFuture<>();
    // Cleanup is owned by the lifecycle executor; admission/count changes use this monitor.
    private boolean cleaned;
    private boolean stopping;
    private final @NonNull String activationId = UUID.randomUUID().toString();
    private final @NonNull Map<PluginAwait, PluginAwait> waits = new ConcurrentHashMap<>();

    private final @NonNull Map<Object, Runnable> resources = new ConcurrentHashMap<>();
    private final @NonNull Deque<@NonNull AutoCloseable> contributedResources = new ArrayDeque<>();

    /** Accepts a lifecycle contribution during construction or while the plugin is active. */
    public void registerResource(@NonNull AutoCloseable resource) {
        synchronized (lock) {
            if (state == PluginState.STOPPING
                    || state == PluginState.CLOSED
                    || state == PluginState.FAILED
                    || state == PluginState.DECLINED)
                throw new IllegalStateException("Plugin resource registration is closed");
            contributedResources.addFirst(resource);
        }
    }

    private final @NonNull Map<Object, Runnable> stoppingResources = new ConcurrentHashMap<>();

    // Shared monitor: guards state transitions here together with the admission slot count.
    private final @NonNull Object lock = new Object();
    private final @NonNull InvocationAdmission admission;

    /** Host cancellation signals that must run before waiting for admitted calls to drain. */
    public void ownStoppingResource(@NonNull Object identity, @NonNull Runnable release) {
        synchronized (lock) {
            if (state != PluginState.ACTIVE)
                throw new IllegalStateException("Plugin is not active");
            stoppingResources.putIfAbsent(identity, release);
        }
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
    public void ownResource(@NonNull Runnable release) {
        synchronized (lock) {
            if (state != PluginState.ACTIVE)
                throw new IllegalStateException("Plugin is not active");
            resources.putIfAbsent(release, release);
        }
    }

    /** Registers a host resource under an identity; returns {@code false} if already present. */
    public boolean ownResource(@NonNull Object identity, @NonNull Runnable release) {
        synchronized (lock) {
            if (state != PluginState.ACTIVE)
                throw new IllegalStateException("Plugin is not active");
            return resources.putIfAbsent(identity, release) == null;
        }
    }

    /** Drops a previously owned resource so its release callback no longer runs on cleanup. */
    public void releaseResource(@NonNull Object identity) {
        resources.remove(identity);
        stoppingResources.remove(identity);
    }

    /** Only the lifecycle callback may use a revoked handle for its final cleanup. */
    public boolean cleaningResources() {
        return (state == PluginState.STOPPING || state == PluginState.FAILED) && onControlThread();
    }

    /** Waiting does not retain an invocation admission; stopping fails it immediately. */
    public @NonNull PluginAwait ownAwait(@NonNull PluginAwait signal) {
        CompletableFuture<Boolean> ready = new CompletableFuture<>();
        var owned = new PluginAwait(bindingId() + "/" + signal.token(), ready);
        synchronized (lock) {
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
    public PluginLifecycle(@NonNull VetoPlugin plugin, @NonNull ExecutorService lifecycle) {
        this.plugin = plugin;
        this.installed = null;
        this.lifecycle = lifecycle;
        this.admission = new InvocationAdmission(lock);
    }

    /** Wraps a discovered package; its real plugin is constructed with the bound context. */
    public PluginLifecycle(@NonNull InstalledPlugin installed, @NonNull ExecutorService lifecycle) {
        this.plugin = null;
        this.installed = installed;
        this.lifecycle = lifecycle;
        this.admission = new InvocationAdmission(lock);
    }

    public @NonNull VetoPlugin implementation() {
        VetoPlugin current = plugin;
        if (current == null) throw new IllegalStateException("Plugin has not been constructed");
        return current;
    }

    public @NonNull PluginIdentity identity() {
        InstalledPlugin descriptor = installed;
        return descriptor == null ? implementation().identity() : descriptor.identity();
    }

    /** Returns entry metadata without requiring construction. */
    public @NonNull String displayName() {
        InstalledPlugin descriptor = installed;
        return descriptor == null ? implementation().displayName() : descriptor.displayName();
    }

    /** Returns historical identities after construction; none are available from a bare package. */
    public @NonNull Set<@NonNull String> historicalIds() {
        VetoPlugin current = plugin;
        return current == null ? Set.of() : current.historicalIds();
    }

    /** Returns a tool-name preference without requiring construction. */
    public String preferredToolName(@NonNull String localId) {
        VetoPlugin current = plugin;
        return current == null ? null : current.preferredToolName(localId);
    }

    public @NonNull PluginState state() {
        return state;
    }

    /** Whether this thread is currently running a control-executor lifecycle task. */
    static boolean onControlThread() {
        return Boolean.TRUE.equals(controlling.get());
    }

    /** A unit of plugin work admitted only while the plugin lifecycle permits it. */
    @FunctionalInterface
    @SuppressWarnings("NullableProblems") // the @NonNull bound is required by the NullnessChecker
    public interface Operation<T extends @NonNull Object> {
        /** Performs the admitted work, or throws {@link PluginFailure} if it cannot complete. */
        @NonNull T run() throws PluginFailure;
    }

    /** Constructs or binds the plugin on the control executor before it starts. */
    public void construct(
            @NonNull PluginContext context, JsonValue.@NonNull ObjectValue configuration)
            throws PluginFailure {
        requireExternalControl();
        await(
                submit(
                        () -> {
                            require(PluginState.NEW);
                            state = PluginState.INITIALIZING;
                            try {
                                if (!identity().equals(context.identity()))
                                    throw new PluginFailure(
                                            PluginFailure.Code.INVALID_CONFIGURATION);
                                var handlers = boundHandlers(context);
                                var bound =
                                        new PluginContext(
                                                context.identity(),
                                                this::fail,
                                                this::state,
                                                context.hostServices(),
                                                handlers);
                                InstalledPlugin descriptor = installed;
                                if (descriptor != null)
                                    plugin = descriptor.create(bound, configuration);
                                VetoPlugin current = implementation();
                                if (!identity().equals(current.identity()))
                                    throw new PluginFailure(
                                            PluginFailure.Code.INVALID_CONFIGURATION);
                                if (current instanceof ScriptPlugin script)
                                    script.bind(bound, configuration);
                                state = PluginState.INITIALIZED;
                                return Boolean.TRUE;
                            } catch (PluginDeclinedException declined) {
                                state = PluginState.STOPPING;
                                signalStopping();
                                cleanup();
                                if (state == PluginState.FAILED) {
                                    closed.complete(null);
                                    throw new PluginFailure(PluginFailure.Code.INTERNAL_FAILURE);
                                }
                                state = PluginState.DECLINED;
                                closed.complete(null);
                                throw declined;
                            } catch (Throwable failure) {
                                failOnControlThread();
                                throw safe(failure);
                            }
                        }));
    }

    private @NonNull Map<@NonNull ContributionPoint<?>, @NonNull Consumer<@NonNull Contribution<?>>>
            boundHandlers(@NonNull PluginContext context) {
        Map<ContributionPoint<?>, Consumer<Contribution<?>>> handlers =
                new HashMap<ContributionPoint<?>, Consumer<Contribution<?>>>(context.handlers()) {
                    @Override
                    public Consumer<Contribution<?>> get(Object point) {
                        if (point == null) return null;
                        if (StandardContributionPoints.RESOURCES.equals(point))
                            return super.get(point);
                        return context.handlers().get(point);
                    }
                };
        handlers.put(
                StandardContributionPoints.RESOURCES,
                contribution -> {
                    if (!(contribution.implementation() instanceof AutoCloseable resource))
                        throw new IllegalArgumentException("Resource contribution required");
                    registerResource(resource);
                });
        return handlers;
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
                                implementation().start();
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
    public <T extends @NonNull Object> @NonNull T execute(@NonNull Operation<T> operation)
            throws PluginFailure {
        return admission.execute(operation, this::state, this::releaseSlot);
    }

    /** Releases one admitted slot and re-checks close completion on the control executor. */
    void releaseSlot() throws PluginFailure {
        await(
                submit(
                        () -> {
                            synchronized (lock) {
                                admission.decrement();
                            }
                            finishClose();
                            return true;
                        }));
    }

    @Override
    public void close() {
        requireExternalControl();
        if (closed.isDone()) return;
        submit(
                () -> {
                    synchronized (lock) {
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
        synchronized (lock) {
            if (state == PluginState.CLOSED || state == PluginState.FAILED) return;
            state = PluginState.FAILED;
        }
        // Failure aborts owned resources to unblock outstanding I/O; graceful close drains first.
        signalStopping();
        cleanup();
        finishClose();
    }

    private void finishClose() {
        synchronized (lock) {
            if (admission.activeCalls() != 0
                    || (state != PluginState.STOPPING && state != PluginState.FAILED)) return;
        }
        cleanup();
        if (state != PluginState.FAILED) state = PluginState.CLOSED;
        closed.complete(null);
    }

    private void signalStopping() {
        if (stopping) return;
        stopping = true;
        stopWaits();
        Error fatal = null;
        for (var release : stoppingResources.values()) {
            try {
                release.run();
            } catch (Throwable failure) {
                state = PluginState.FAILED;
                fatal = preserveFatal(fatal, failure);
            }
        }
        stoppingResources.clear();
        VetoPlugin current = plugin;
        if (current != null) {
            try {
                current.stopping();
            } catch (Throwable failure) {
                state = PluginState.FAILED;
                fatal = preserveFatal(fatal, failure);
            }
        }
        if (state == PluginState.FAILED) cleanup(fatal);
    }

    private void cleanup() {
        cleanup(null);
    }

    private void cleanup(Error fatal) {
        if (cleaned) {
            if (fatal != null) {
                closed.completeExceptionally(fatal);
                throw fatal;
            }
            return;
        }
        cleaned = true;
        active.completeExceptionally(new IllegalStateException("Plugin stopped before activation"));
        try {
            VetoPlugin current = plugin;
            if (current != null) current.close();
        } catch (Throwable failure) {
            state = PluginState.FAILED;
            fatal = preserveFatal(fatal, failure);
        } finally {
            while (!contributedResources.isEmpty()) {
                try {
                    contributedResources.removeFirst().close();
                } catch (Throwable failure) {
                    state = PluginState.FAILED;
                    fatal = preserveFatal(fatal, failure);
                }
            }
            InstalledPlugin descriptor = installed;
            if (descriptor != null) {
                try {
                    descriptor.close();
                } catch (Throwable failure) {
                    state = PluginState.FAILED;
                    fatal = preserveFatal(fatal, failure);
                }
            }
            for (var release : resources.values()) {
                try {
                    release.run();
                } catch (Throwable failure) {
                    state = PluginState.FAILED;
                    fatal = preserveFatal(fatal, failure);
                }
            }
            resources.clear();
        }
        if (fatal != null) {
            closed.completeExceptionally(fatal);
            throw fatal;
        }
    }

    // ThreadDeath remains a supported fatal signal and must propagate while the JDK retains it.
    @SuppressWarnings("removal")
    private static Error preserveFatal(Error fatal, @NonNull Throwable failure) {
        if (fatal != null) return fatal;
        return failure instanceof Error error
                        && (error instanceof VirtualMachineError || error instanceof ThreadDeath)
                ? error
                : null;
    }

    private void require(@NonNull PluginState expected) throws PluginFailure {
        if (state != expected) throw new PluginFailure(PluginFailure.Code.NOT_READY);
    }

    private void requireExternalControl() {
        if (onControlThread() || InvocationAdmission.inInvocation())
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
            if (failure.getCause() instanceof PluginDeclinedException declined) throw declined;
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
