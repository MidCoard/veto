package top.focess.veto.integration.plugins;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.capability.CapabilityAccess;
import top.focess.veto.agent.capability.HttpDestinationGrant;
import top.focess.veto.agent.tool.ToolCallContext;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.api.plugin.agent.IsolatedAgent;

/** Host-issued isolated runtime context. Counters are atomic; cancellation flags are volatile. */
public final class IsolatedExecutionContext implements IsolatedAgent.Runtime {
    private final @NonNull ToolCallContext parent;
    private final @NonNull Thread parentThread;
    private final @NonNull BooleanSupplier admitted;
    private final @NonNull String id;
    private final @NonNull String model;
    private final long started = System.nanoTime();
    final long deadline;
    private final IsolatedAgent.@NonNull Terminal terminal;
    final IsolatedAgent.@NonNull Limits limits;
    @NonNull Set<@NonNull String> privateTools = Set.of();
    final @NonNull AtomicInteger calls = new AtomicInteger();
    final @NonNull AtomicLong input = new AtomicLong(), output = new AtomicLong();
    volatile int observationBudget;
    @NonNull Runnable checkTools = () -> {};
    volatile boolean closed;
    volatile boolean completed;
    HttpDestinationGrant destination;

    IsolatedExecutionContext(
            @NonNull ToolCallContext parent,
            @NonNull Thread parentThread,
            @NonNull BooleanSupplier admitted,
            @NonNull String id,
            @NonNull String model,
            IsolatedAgent.@NonNull Limits limits,
            IsolatedAgent.@NonNull Terminal terminal) {
        this.parent = parent;
        this.parentThread = parentThread;
        this.admitted = admitted;
        this.id = id;
        this.model = model;
        this.deadline = started + limits.timeout().toNanos();
        this.terminal = terminal;
        this.limits = limits;
    }

    public @NonNull String id() {
        return id;
    }

    public long deadline() {
        return deadline;
    }

    public @NonNull ToolCallContext parent() {
        return parent;
    }

    /** Binds the one HTTP destination grant this execution may use; binding twice fails. */
    public void bind(@NonNull HttpDestinationGrant grant) {
        authorizeParent();
        if (destination != null) throw new SecurityException("Destination already bound");
        destination = grant;
    }

    /** Verifies the caller is still the original parent invocation and it is not cancelled. */
    public void authorizeParent() {
        var context = ToolCallContextHolder.get();
        if (context == null
                || !parent.equals(CapabilityAccess.require(context.executionPermit().capability())))
            throw new SecurityException("Parent invocation changed");
        if (parentThread.isInterrupted() || !admitted.getAsBoolean())
            throw new CancellationException("Parent invocation cancelled");
    }

    /** Throws {@link CancellationException} once closed, cancelled, or past the deadline. */
    public void check() {
        checkTools.run();
        if (closed
                || Thread.currentThread().isInterrupted()
                || parentThread.isInterrupted()
                || !admitted.getAsBoolean())
            throw new CancellationException("Isolated execution cancelled");
        if (System.nanoTime() >= deadline)
            throw new CancellationException("Isolated execution deadline exceeded");
    }

    /** Verifies the current tool invocation is the given private tool of this execution. */
    public void authorize(@NonNull String operation) {
        if (!privateTools.contains(operation))
            throw new SecurityException("Operation is not a private tool");
        var context = ToolCallContextHolder.get();
        if (context == null) throw new SecurityException("No private tool invocation");
        CapabilityAccess.require(parent.executionPermit().capability(), operation);
        if (!id.equals(context.agentId())
                || !parent.userId().equals(context.userId())
                || !Objects.equals(parent.sessionId(), context.sessionId()))
            throw new SecurityException("Private tool belongs to another execution");
        check();
    }

    /** True while the parent invocation remains admitted and the deadline has not passed. */
    public boolean provenanceLive() {
        return !parentThread.isInterrupted()
                && admitted.getAsBoolean()
                && System.nanoTime() < deadline;
    }

    /** Input bytes still available for observations in the next model call. */
    public int observationBudgetBytes() {
        return observationBudget;
    }

    public IsolatedAgent.@NonNull Usage usage() {
        return new IsolatedAgent.Usage(
                id,
                model,
                Duration.ofNanos(System.nanoTime() - started).toMillis(),
                calls.get(),
                input.get(),
                output.get());
    }

    /** Settles the declaring invocation with the result through the terminal tool. */
    public void complete(@NonNull String result) {
        authorize(terminal.tool());
        completed = true;
        ToolCallContextHolder.finish(result);
    }
}
