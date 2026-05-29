package top.focess.veto.orchestrator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * C5 Circuit Breaker  - prevents cascading failures by tracking consecutive errors
 * and tripping to OPEN state when thresholds are exceeded.
 * After a reset period, transitions to HALF_OPEN to test recovery.
 */
@Component
public class CircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    private final OrchestratorConfiguration config;
    private final AtomicReference<CircuitState> state = new AtomicReference<>(CircuitState.CLOSED);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicLong lastFailureTime = new AtomicLong(0);
    private final AtomicLong lastStateChange = new AtomicLong(System.currentTimeMillis());

    public CircuitBreaker(OrchestratorConfiguration config) {
        this.config = config;
    }

    /**
     * Check if the circuit breaker allows execution.
     */
    public boolean allowRequest() {
        CircuitState currentState = state.get();

        switch (currentState) {
            case CLOSED:
                return true;
            case OPEN:
                // Check if reset period has elapsed
                long elapsed = System.currentTimeMillis() - lastStateChange.get();
                if (elapsed >= config.getCircuitBreakerResetMs()) {
                    if (state.compareAndSet(CircuitState.OPEN, CircuitState.HALF_OPEN)) {
                        log.info("C5 CircuitBreaker: OPEN -> HALF_OPEN (reset period elapsed)");
                        return true;
                    }
                }
                log.warn("C5 CircuitBreaker: Request blocked (state=OPEN, failures={})", failureCount.get());
                return false;
            case HALF_OPEN:
                return true;
            default:
                return true;
        }
    }

    /**
     * Record a successful execution.
     */
    public synchronized void recordSuccess() {
        CircuitState currentState = state.get();
        if (currentState == CircuitState.HALF_OPEN) {
            // Recovery confirmed
            state.set(CircuitState.CLOSED);
            failureCount.set(0);
            lastStateChange.set(System.currentTimeMillis());
            log.info("C5 CircuitBreaker: HALF_OPEN -> CLOSED (recovery confirmed)");
        }
        failureCount.set(0);
    }

    /**
     * Record a failed execution.
     */
    public synchronized void recordFailure() {
        int failures = failureCount.incrementAndGet();
        lastFailureTime.set(System.currentTimeMillis());

        if (failures >= config.getCircuitBreakerThreshold()) {
            CircuitState currentState = state.get();
            if (currentState == CircuitState.CLOSED || currentState == CircuitState.HALF_OPEN) {
                state.set(CircuitState.OPEN);
                lastStateChange.set(System.currentTimeMillis());
                log.warn("C5 CircuitBreaker: {} -> OPEN (threshold={} failures reached)",
                    currentState, config.getCircuitBreakerThreshold());
            }
        }
    }

    /**
     * Reset the circuit breaker to closed state.
     */
    public void reset() {
        state.set(CircuitState.CLOSED);
        failureCount.set(0);
        lastStateChange.set(System.currentTimeMillis());
        log.info("C5 CircuitBreaker: Reset to CLOSED");
    }

    public CircuitState getState() { return state.get(); }
    public int getFailureCount() { return failureCount.get(); }

    public enum CircuitState {
        CLOSED,    // Normal operation
        OPEN,      // Rejecting requests
        HALF_OPEN  // Testing recovery
    }
}
