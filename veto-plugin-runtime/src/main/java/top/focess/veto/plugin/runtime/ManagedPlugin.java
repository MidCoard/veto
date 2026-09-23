package top.focess.veto.plugin.runtime;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.veto.api.plugin.*;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginContributions;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.VetoPlugin;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.PluginFailure;

/** Host-owned state and invocation admission, serialized on the manager control executor. */
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
    private final @NonNull CompletableFuture<Void> closed = new CompletableFuture<>();
    // Accessed only by the lifecycle executor.
    private boolean cleaned;
    private int activeCalls;
    private final @NonNull String activationId = java.util.UUID.randomUUID().toString();

    public @NonNull String bindingId() {
        return "plugin:" + identity().id() + ":" + identity().version() + ":" + activationId;
    }

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

    public final @NonNull PluginState state() {
        return state;
    }

    @FunctionalInterface
    public interface Operation<T extends @NonNull Object> {
        @NonNull T run() throws PluginFailure;
    }

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
                            } catch (Throwable failure) {
                                failOnControlThread();
                                throw safe(failure);
                            }
                            return true;
                        }));
    }

    /** Admission and shutdown are ordered together; the handler runs outside the control thread. */
    public final <T extends @NonNull Object> @NonNull T execute(@NonNull Operation<T> operation)
            throws PluginFailure {
        if (Boolean.TRUE.equals(controlling.get()))
            throw new PluginFailure(PluginFailure.Code.NOT_READY);
        // A same-thread nested adapter belongs to the already admitted invocation.
        if (Boolean.TRUE.equals(invoking.get())) return operation.run();
        await(
                submit(
                        () -> {
                            require(PluginState.ACTIVE);
                            activeCalls++;
                            return true;
                        }));
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
                                activeCalls--;
                                finishClose();
                                return true;
                            }));
        }
        if (state == PluginState.FAILED) throw new PluginFailure(PluginFailure.Code.NOT_READY);
        return result;
    }

    @Override
    public final void close() {
        requireExternalControl();
        if (closed.isDone()) return;
        submit(
                () -> {
                    if (state != PluginState.CLOSED && state != PluginState.FAILED)
                        state = PluginState.STOPPING;
                    finishClose();
                    return true;
                });
        // Do not block the control thread: completions must still be able to release their slots.
        closed.join();
    }

    /** Failure callbacks only report an event; cleanup always runs on the control thread. */
    protected final void fail() {
        submit(
                () -> {
                    failOnControlThread();
                    return true;
                });
    }

    private void failOnControlThread() {
        if (state == PluginState.CLOSED || state == PluginState.FAILED) return;
        state = PluginState.FAILED;
        // Failure aborts owned resources to unblock outstanding I/O; graceful close drains first.
        cleanup();
        finishClose();
    }

    private void finishClose() {
        if (activeCalls != 0 || (state != PluginState.STOPPING && state != PluginState.FAILED))
            return;
        cleanup();
        if (state != PluginState.FAILED) state = PluginState.CLOSED;
        closed.complete(null);
    }

    private void cleanup() {
        if (cleaned) return;
        cleaned = true;
        try {
            plugin.close();
        } catch (Throwable failure) {
            state = PluginState.FAILED;
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
