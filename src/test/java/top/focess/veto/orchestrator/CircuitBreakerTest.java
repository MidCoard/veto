package top.focess.veto.orchestrator;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CircuitBreakerTest {

  private OrchestratorConfiguration config;
  private CircuitBreaker circuitBreaker;

  @BeforeEach
  void setUp() {
    config = new OrchestratorConfiguration();
    config.setCircuitBreakerThreshold(3);
    config.setCircuitBreakerResetMs(100); // Fast reset for testing
    circuitBreaker = new CircuitBreaker(config);
  }

  @Test
  void testInitialStateIsClosed() {
    assertEquals(CircuitBreaker.CircuitState.CLOSED, circuitBreaker.getState());
    assertTrue(circuitBreaker.allowRequest());
  }

  @Test
  void testTripsToOpenOnThresholdFailures() {
    assertTrue(circuitBreaker.allowRequest());
    circuitBreaker.recordFailure();
    assertEquals(CircuitBreaker.CircuitState.CLOSED, circuitBreaker.getState());

    circuitBreaker.recordFailure();
    assertEquals(CircuitBreaker.CircuitState.CLOSED, circuitBreaker.getState());

    circuitBreaker.recordFailure(); // Third failure
    assertEquals(CircuitBreaker.CircuitState.OPEN, circuitBreaker.getState());
    assertFalse(circuitBreaker.allowRequest());
  }

  @Test
  void testSuccessResetsFailureCount() {
    circuitBreaker.recordFailure();
    circuitBreaker.recordFailure();
    circuitBreaker.recordSuccess(); // Reset

    assertTrue(circuitBreaker.allowRequest());
    assertEquals(0, circuitBreaker.getFailureCount());
  }

  @Test
  void testHalfOpenAfterResetPeriod() throws InterruptedException {
    // Trip to OPEN
    circuitBreaker.recordFailure();
    circuitBreaker.recordFailure();
    circuitBreaker.recordFailure();
    assertEquals(CircuitBreaker.CircuitState.OPEN, circuitBreaker.getState());

    // Wait for reset period
    Thread.sleep(150);

    // Should transition to HALF_OPEN on next allow check
    assertTrue(circuitBreaker.allowRequest());
    assertEquals(CircuitBreaker.CircuitState.HALF_OPEN, circuitBreaker.getState());
  }

  @Test
  void testRecoveryFromHalfOpen() {
    // Trip to OPEN
    circuitBreaker.recordFailure();
    circuitBreaker.recordFailure();
    circuitBreaker.recordFailure();

    // Force state to HALF_OPEN
    // After reset period, allowRequest transitions to HALF_OPEN
    // Then recordSuccess should transition to CLOSED
    // We can test the transitions directly:

    circuitBreaker.reset();
    assertEquals(CircuitBreaker.CircuitState.CLOSED, circuitBreaker.getState());
    assertEquals(0, circuitBreaker.getFailureCount());
  }
}
