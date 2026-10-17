package top.focess.veto.integration.plugins;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.AgentRunner;
import top.focess.veto.agent.RequestHandle;
import top.focess.veto.agent.VetoAgent;
import top.focess.veto.api.agent.AgentResult;
import top.focess.veto.api.agent.AgentState;
import top.focess.veto.api.plugin.agent.AgentHost;
import top.focess.veto.api.plugin.agent.IsolatedAgent;

/**
 * Isolated child owned by its parent invocation; submission and cleanup synchronize on this agent.
 */
public final class IsolatedChildAgent implements IsolatedAgent {
    private final @NonNull IsolatedExecutionContext scope;
    private final @NonNull Runnable releaseContext;
    private final IsolatedAgent.@NonNull Tools tools;
    private final @NonNull VetoAgent agent;
    private final @NonNull AgentRunner runner;
    private RequestHandle request;
    private volatile boolean closed;
    private final @NonNull AtomicBoolean closeRequested = new AtomicBoolean();
    private final @NonNull AtomicLong terminationDeadline = new AtomicLong();
    private @NonNull Runnable onClosed = () -> {};

    /** Registers the close callback, run immediately when the child is already closed. */
    public synchronized void onClosed(@NonNull Runnable callback) {
        if (closed) callback.run();
        else onClosed = callback;
    }

    IsolatedChildAgent(
            @NonNull IsolatedExecutionContext scope,
            IsolatedAgent.@NonNull Tools tools,
            @NonNull VetoAgent agent,
            @NonNull AgentRunner runner,
            @NonNull Runnable releaseContext) {
        this.scope = scope;
        this.releaseContext = releaseContext;
        this.tools = tools;
        this.agent = agent;
        this.runner = runner;
        agent.onTermination(
                () ->
                        Thread.ofVirtual()
                                .name("isolated-cleanup-" + scope.id())
                                .start(
                                        () -> {
                                            boolean interrupted = false;
                                            try {
                                                while (true) {
                                                    try {
                                                        if (agent.awaitTermination(
                                                                Duration.ofSeconds(1))) break;
                                                    } catch (InterruptedException stopped) {
                                                        interrupted = true;
                                                    }
                                                }
                                                cleanup();
                                            } finally {
                                                if (interrupted) Thread.currentThread().interrupt();
                                            }
                                        }));
    }

    public @NonNull IsolatedExecutionContext scope() {
        return scope;
    }

    public @NonNull String id() {
        return agent.id();
    }

    public @NonNull AgentState state() {
        return agent.state();
    }

    public IsolatedAgent.@NonNull Limits limits() {
        return scope.limits;
    }

    public IsolatedAgent.@NonNull Usage usage() {
        return scope.usage();
    }

    public boolean budgetExhausted() {
        return runner.budgetExhausted();
    }

    /** Submits the single request this child accepts; a second submission fails. */
    public synchronized AgentHost.@NonNull Request submit(@NonNull String prompt) {
        scope.authorizeParent();
        scope.check();
        if (request != null) throw new IllegalStateException("Isolated child accepts one request");
        var handle = agent.submitRequest(prompt);
        request = handle;
        return new AgentHost.Request() {
            public @NonNull String id() {
                return handle.requestId();
            }

            public @NonNull CompletableFuture<@NonNull AgentResult> result() {
                return handle.result().copy();
            }

            public @NonNull CompletableFuture<@NonNull Boolean> settled() {
                return handle.settled().copy();
            }

            public boolean cancel(@NonNull Duration timeout) throws InterruptedException {
                return agent.cancelTask(handle, timeout);
            }
        };
    }

    /** True once closed after {@code complete} and the submitted request succeeded. */
    public boolean settledSuccessfully() {
        var active = request;
        return closed
                && scope.completed
                && active != null
                && active.settled().isDone()
                && active.result().isDone()
                && active.result().join().success();
    }

    /** Blocks until the child agent terminates or the timeout elapses; true when terminated. */
    public boolean awaitTermination(@NonNull Duration timeout) throws InterruptedException {
        return agent.awaitTermination(timeout);
    }

    private synchronized void cleanup() {
        if (closed) return;
        scope.closed = true;
        closed = true;
        releaseContext.run();
        try {
            tools.close();
        } finally {
            onClosed.run();
        }
    }

    /**
     * Terminates the child and releases its private tools, waiting briefly for exit.
     *
     * @throws IllegalStateException when termination does not finish within the close deadline
     */
    public void close() {
        if (closed) return;
        scope.closed = true;
        long deadline =
                terminationDeadline.updateAndGet(
                        existing ->
                                existing == 0
                                        ? System.nanoTime() + Duration.ofSeconds(2).toNanos()
                                        : existing);
        if (closeRequested.compareAndSet(false, true)) agent.terminate();
        boolean interrupted = Thread.interrupted();
        try {
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0)
                    throw new IllegalStateException(
                            "Isolated execution did not terminate within the close deadline;"
                                    + " cleanup remains pending");
                try {
                    if (agent.awaitTermination(Duration.ofNanos(remaining))) {
                        cleanup();
                        return;
                    }
                } catch (InterruptedException stopped) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
