package top.focess.veto.orchestrator;

import top.focess.veto.model.ToolExecutionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * C5 Swarm Lifecycle  - an isolated worker process for executing sub-tasks.
 * Workers are spawned by the SwarmOrchestrator and run with strict resource isolation.
 */
public class WorkerProcess {

    private static final Logger log = LoggerFactory.getLogger(WorkerProcess.class);

    private final String id;
    private final OrchestratorConfiguration config;
    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final AtomicReference<ToolExecutionRequest> currentTask = new AtomicReference<>(null);
    private final AtomicReference<Instant> lastActivity = new AtomicReference<>(Instant.now());
    private final AtomicReference<Instant> startedAt = new AtomicReference<>(Instant.now());
    private final CompletableFuture<Void> completionFuture = new CompletableFuture<>();

    public WorkerProcess(OrchestratorConfiguration config) {
        this.id = UUID.randomUUID().toString().substring(0, 8);
        this.config = config;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "veto-worker-" + id);
            t.setDaemon(true);
            return t;
        });
        log.debug("C5 Worker[{}]: Spawned", id);
    }

    /**
     * Execute a task on this worker. Returns a future that completes with the result.
     */
    public CompletableFuture<String> execute(ToolExecutionRequest task,
                                             java.util.function.Function<ToolExecutionRequest, String> executorFn) {
        if (!running.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Worker[" + id + "] is shutdown"));
        }
        if (!busy.compareAndSet(false, true)) {
            return CompletableFuture.failedFuture(new IllegalStateException("Worker[" + id + "] is busy"));
        }

        currentTask.set(task);
        lastActivity.set(Instant.now());
        task.markRunning();

        log.info("C5 Worker[{}]: Executing task '{}'", id, task.getCapabilityName());

        return CompletableFuture.supplyAsync(() -> {
            try {
                String result = executorFn.apply(task);
                task.markCompleted(result);
                log.info("C5 Worker[{}]: Task '{}' completed", id, task.getCapabilityName());
                return result;
            } catch (Exception e) {
                task.markFailed(e.getMessage());
                log.error("C5 Worker[{}]: Task '{}' failed: {}", id, task.getCapabilityName(), e.getMessage());
                throw e;
            } finally {
                busy.set(false);
                currentTask.set(null);
                lastActivity.set(Instant.now());
            }
        }, executor);
    }

    /**
     * Check if this worker has been idle too long.
     */
    public boolean isIdleTimedOut() {
        if (busy.get()) return false;
        long idleMs = Instant.now().toEpochMilli() - lastActivity.get().toEpochMilli();
        return idleMs > config.getWorkerIdleTimeoutMs();
    }

    /**
     * Forcefully kill this worker's current task.
     */
    public void kill() {
        log.warn("C5 Worker[{}]: Kill signal received", id);
        executor.shutdownNow();
        running.set(false);
        busy.set(false);
        completionFuture.complete(null);
    }

    public boolean isRunning() { return running.get(); }
    public boolean isBusy() { return busy.get(); }
    public String getId() { return id; }
    public Optional<ToolExecutionRequest> getCurrentTask() {
        return Optional.ofNullable(currentTask.get());
    }
    public Instant getLastActivity() { return lastActivity.get(); }
    public Instant getStartedAt() { return startedAt.get(); }
}
