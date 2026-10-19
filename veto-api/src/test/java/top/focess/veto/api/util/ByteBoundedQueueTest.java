package top.focess.veto.api.util;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ByteBoundedQueueTest {
    @Test
    void bothLimitsAreReleasedByPollingAndClearingWithoutReordering() {
        var queue = new ByteBoundedQueue<String>(2, 10);
        assertTrue(queue.offer("first", 6));
        assertFalse(queue.offer("too many bytes", 5));
        assertTrue(queue.offer("second", 4));
        assertFalse(queue.offer("no slots", 0));
        assertEquals("first", queue.poll());
        assertFalse(queue.offer("still too large", 7));
        assertTrue(queue.offer("third", 6));
        assertEquals("second", queue.poll());
        assertEquals("third", queue.poll());
        assertNull(queue.poll());
        assertTrue(queue.isEmpty());
        assertTrue(queue.offer("full byte budget", 10));
        queue.clear();
        assertTrue(queue.isEmpty());
        assertTrue(queue.offer("new full budget", 10));
        assertTrue(queue.offer("zero bytes", 0));
        assertFalse(queue.offer("third entry", 0));
    }

    @Test
    void invalidCostsCannotCorruptAccountingOrOverflowAdmission() {
        assertThrows(IllegalArgumentException.class, () -> new ByteBoundedQueue<>(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new ByteBoundedQueue<>(1, 0));
        var queue = new ByteBoundedQueue<String>(2, Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> queue.offer("negative", -1));
        assertTrue(queue.offer("one", 1));
        assertFalse(queue.offer("would overflow addition", Integer.MAX_VALUE));
        assertEquals("one", queue.poll());
        assertTrue(queue.offer("maximum", Integer.MAX_VALUE));
        assertEquals("maximum", queue.poll());
        assertNull(queue.poll());
    }
}
