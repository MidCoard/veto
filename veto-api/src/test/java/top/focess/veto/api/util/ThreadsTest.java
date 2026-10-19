package top.focess.veto.api.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(5)
class ThreadsTest {
    @Test
    void interruptionEndsTheWaitWithoutStoppingTheWorker() throws Exception {
        var release = new CompletableFuture<Void>();
        var worker = Thread.ofVirtual().start(release::join);
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedException.class, () -> Threads.joinAll(List.of(worker)));
            assertFalse(Thread.currentThread().isInterrupted());
            assertTrue(worker.isAlive());
            Thread.currentThread().interrupt();
            assertThrows(InterruptedException.class, () -> Threads.awaitTermination(worker, 0));
            assertFalse(Thread.currentThread().isInterrupted());
            assertTrue(worker.isAlive());
        } finally {
            assertTrue(release.complete(null));
            worker.join(1_000);
        }
        assertFalse(worker.isAlive());
    }

    @Test
    void deadlineDoesNotStopTheWorker() throws InterruptedException {
        var release = new CompletableFuture<Void>();
        var worker = Thread.ofVirtual().start(release::join);
        try {
            Threads.awaitTermination(worker, 20);
            assertTrue(worker.isAlive());
        } finally {
            assertTrue(release.complete(null));
            Threads.awaitTermination(worker, 1_000);
        }
        assertFalse(worker.isAlive());
        Threads.awaitTermination(Thread.currentThread(), 0);
        Threads.awaitTermination(null, 0);
    }
}
