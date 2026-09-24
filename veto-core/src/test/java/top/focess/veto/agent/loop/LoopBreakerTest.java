package top.focess.veto.agent.loop;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LoopBreakerTest {
    @Test
    void explicitContinueAddsSegmentsWithoutErasingConsumption() {
        var breaker = new LoopBreaker(2);
        assertEquals(2, breaker.grantedCalls());
        assertThrows(IllegalStateException.class, breaker::grantContinuation);
        for (int segment = 1; segment <= 3; segment++) {
            breaker.recordModelCall();
            breaker.recordModelCall();
            assertEquals(segment * 2L, breaker.count());
            assertTrue(breaker.shouldTrip());
            breaker.grantContinuation();
            assertEquals((segment + 1) * 2L, breaker.grantedCalls());
            assertFalse(breaker.shouldTrip());
        }
        breaker.newEpisode();
        assertEquals(0, breaker.count());
        assertEquals(2, breaker.grantedCalls());
    }

    @Test
    void restorationPreservesSavedAllowanceAndLegacyUsesOnlyOneSegment() {
        var breaker = new LoopBreaker(2);
        breaker.restore(5, 6);
        assertEquals(5, breaker.count());
        assertEquals(6, breaker.grantedCalls());
        breaker.recordModelCall();
        assertTrue(breaker.shouldTrip());
        breaker.restore(6, 6);
        assertTrue(breaker.shouldTrip());
        breaker.restoreCount(3);
        assertEquals(2, breaker.grantedCalls());
        assertTrue(breaker.shouldTrip());
    }

    @Test
    void unlimitedAndOverflowNeverWrapOrInventNegativeConsumption() {
        var unlimited = new LoopBreaker(-1);
        unlimited.restore(Long.MAX_VALUE, -1);
        unlimited.recordModelCall();
        assertEquals(Long.MAX_VALUE, unlimited.count());
        assertFalse(unlimited.shouldTrip());
        var finite = new LoopBreaker(Long.MAX_VALUE - 1);
        finite.restore(Long.MAX_VALUE - 1, Long.MAX_VALUE - 1);
        finite.grantContinuation();
        assertEquals(Long.MAX_VALUE, finite.grantedCalls());
        finite.recordModelCall();
        assertTrue(finite.shouldTrip());
        finite.grantContinuation();
        assertTrue(finite.shouldTrip());
        finite.recordModelCall();
        assertEquals(Long.MAX_VALUE, finite.count());
        assertThrows(IllegalArgumentException.class, () -> finite.restore(-1, 4));
        assertThrows(IllegalArgumentException.class, () -> finite.restore(0, -2));
        var zero = new LoopBreaker(0);
        zero.grantContinuation();
        assertTrue(zero.shouldTrip());
    }
}
