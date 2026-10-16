package top.focess.veto.api.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(5)
class ThreadsTest {
    @Test
    void interruptedShutdownStillWaitsForTerminationAndRestoresInterruption() throws Exception {
        var release = new CompletableFuture<Void>();
        var worker = Thread.ofVirtual().start(release::join);
        var waiting = new CountDownLatch(1);
        var interrupted = new AtomicBoolean();
        var waiter =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    Thread.currentThread().interrupt();
                                    waiting.countDown();
                                    Threads.awaitTermination(worker, 0);
                                    interrupted.set(Thread.currentThread().isInterrupted());
                                });
        try {
            assertTrue(waiting.await(1, TimeUnit.SECONDS));
            assertTrue(waiter.isAlive());
        } finally {
            assertTrue(release.complete(null));
            waiter.join(1_000);
        }
        assertFalse(worker.isAlive());
        assertFalse(waiter.isAlive());
        assertTrue(interrupted.get());
    }

    @Test
    void deadlineDoesNotStopTheWorker() {
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
