package top.focess.veto.orchestrator;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import top.focess.veto.model.ToolExecutionRequest;

/**
 * C5 Swarm Lifecycle Orchestrator - the local process manager. Spawns isolated worker threads for
 * parallel sub-tasks, manages local file-lock contention, and acts as a circuit breaker to kill
 * deadlocked sub-agents.
 */
@Service
public class SwarmOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SwarmOrchestrator.class);

    private final OrchestratorConfiguration config;
    private final CircuitBreaker circuitBreaker;
    private final FileLockManager fileLockManager;

    private final CopyOnWriteArrayList<WorkerProcess> workers = new CopyOnWriteArrayList<>();
    private final AtomicInteger taskCounter = new AtomicInteger(0);
    private ScheduledExecutorService maintenanceScheduler;

    public SwarmOrchestrator(
            OrchestratorConfiguration config,
            CircuitBreaker circuitBreaker,
            FileLockManager fileLockManager) {
        this.config = config;
        this.circuitBreaker = circuitBreaker;
        this.fileLockManager = fileLockManager;
    }

    @PostConstruct
    public void init() {
        // Spawn initial worker pool
        for (int i = 0; i < config.getMaxWorkers(); i++) {
            workers.add(new WorkerProcess(config));
        }

        // Maintenance scheduler for idle cleanup and deadlock detection
        maintenanceScheduler =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r, "veto-swarm-maint");
                            t.setDaemon(true);
                            return t;
                        });
        maintenanceScheduler.scheduleAtFixedRate(this::maintenanceCycle, 30, 30, TimeUnit.SECONDS);

        log.info("C5 SwarmOrchestrator: Initialized with {} workers", workers.size());
    }

    @PreDestroy
    public void shutdown() {
        if (maintenanceScheduler != null) {
            maintenanceScheduler.shutdown();
        }
        workers.forEach(WorkerProcess::kill);
        fileLockManager.releaseAll();
        log.info("C5 SwarmOrchestrator: Shut down");
    }

    /**
     * Execute a tool execution request on an available worker. Uses circuit breaker to prevent
     * cascading failures.
     */
    public CompletableFuture<String> execute(
            ToolExecutionRequest task, Function<ToolExecutionRequest, String> executorFn) {
        if (!circuitBreaker.allowRequest()) {
            task.markFailed("Circuit breaker is OPEN");
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Circuit breaker is OPEN  - rejecting request"));
        }

        WorkerProcess worker = findAvailableWorker();
        if (worker == null) {
            // Scale up if under max
            if (workers.size() < config.getMaxWorkers()) {
                worker = new WorkerProcess(config);
                workers.add(worker);
                log.info("C5 SwarmOrchestrator: Scaled up to {} workers", workers.size());
            } else {
                task.markFailed("No available workers");
                return CompletableFuture.failedFuture(
                        new IllegalStateException("All workers are busy"));
            }
        }

        int taskNum = taskCounter.incrementAndGet();
        log.info(
                "C5 SwarmOrchestrator: Task #{} ('{}') assigned to Worker[{}]",
                taskNum,
                task.getCapabilityName(),
                worker.getId());

        return worker.execute(task, executorFn)
                .whenComplete(
                        (result, error) -> {
                            if (error != null) {
                                circuitBreaker.recordFailure();
                            } else {
                                circuitBreaker.recordSuccess();
                            }
                        });
    }

    /** Find an idle worker, or null if all are busy. */
    private WorkerProcess findAvailableWorker() {
        // Prefer idle workers
        for (WorkerProcess w : workers) {
            if (w.isRunning() && !w.isBusy()) {
                return w;
            }
        }
        return null;
    }

    /** Periodic maintenance: reap idle workers, detect deadlocks. */
    private void maintenanceCycle() {
        try {
            int activeCount = 0;
            int idleCount = 0;
            int killedCount = 0;

            for (WorkerProcess w : workers) {
                if (w.isRunning()) {
                    if (w.isBusy()) {
                        activeCount++;
                        // Detect potential deadlocks (task running > 5 min without completion)
                        if (w.getCurrentTask().isPresent()) {
                            var task = w.getCurrentTask().get();
                            long runningMs =
                                    System.currentTimeMillis() - task.getCreatedAt().toEpochMilli();
                            if (runningMs > 300_000) { // 5 minutes
                                log.warn(
                                        "C5 SwarmOrchestrator: Deadlock detected  - Worker[{}] running '{}' for {}ms",
                                        w.getId(),
                                        task.getCapabilityName(),
                                        runningMs);
                                w.kill();
                                killedCount++;
                                workers.remove(w);
                                // Replace with fresh worker
                                workers.add(new WorkerProcess(config));
                            }
                        }
                    } else {
                        if (w.isIdleTimedOut()) {
                            // Only reap if we have enough workers
                            if (workers.size() > config.getMaxWorkers() / 2) {
                                workers.remove(w);
                                w.kill();
                                killedCount++;
                            }
                        } else {
                            idleCount++;
                        }
                    }
                }
            }

            log.debug(
                    "C5 SwarmOrchestrator: Maintenance  - workers={}, active={}, idle={}, killed={}",
                    workers.size(),
                    activeCount,
                    idleCount,
                    killedCount);

        } catch (Exception e) {
            log.warn("C5 SwarmOrchestrator: Maintenance cycle error", e);
        }
    }

    public int getWorkerCount() {
        return workers.size();
    }

    public int getActiveWorkerCount() {
        return (int) workers.stream().filter(WorkerProcess::isBusy).count();
    }

    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    public FileLockManager getFileLockManager() {
        return fileLockManager;
    }
}
