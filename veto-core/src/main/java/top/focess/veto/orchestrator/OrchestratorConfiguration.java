package top.focess.veto.orchestrator;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Configuration for orchestrator Swarm Lifecycle Orchestrator. */
@Configuration
@ConfigurationProperties(prefix = "veto.orchestrator")
public class OrchestratorConfiguration {

    private int maxWorkers = 4;
    private long workerIdleTimeoutMs = 60000;
    private int circuitBreakerThreshold = 3;
    private long circuitBreakerResetMs = 30000;

    public int getMaxWorkers() {
        return maxWorkers;
    }

    public void setMaxWorkers(int maxWorkers) {
        this.maxWorkers = maxWorkers;
    }

    public long getWorkerIdleTimeoutMs() {
        return workerIdleTimeoutMs;
    }

    public void setWorkerIdleTimeoutMs(long workerIdleTimeoutMs) {
        this.workerIdleTimeoutMs = workerIdleTimeoutMs;
    }

    public int getCircuitBreakerThreshold() {
        return circuitBreakerThreshold;
    }

    public void setCircuitBreakerThreshold(int circuitBreakerThreshold) {
        this.circuitBreakerThreshold = circuitBreakerThreshold;
    }

    public long getCircuitBreakerResetMs() {
        return circuitBreakerResetMs;
    }

    public void setCircuitBreakerResetMs(long circuitBreakerResetMs) {
        this.circuitBreakerResetMs = circuitBreakerResetMs;
    }
}
