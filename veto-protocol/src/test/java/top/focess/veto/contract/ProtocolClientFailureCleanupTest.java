package top.focess.veto.contract;

import static org.junit.jupiter.api.Assertions.*;
import static top.focess.veto.contract.ContractTestSupport.assertInstanceOf;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ProtocolClientFailureCleanupTest {
    @Test
    @Timeout(5)
    void rejectedHandshakeClosesTransportAndPreservesTheOriginalFailure() throws Exception {
        var handshakeFailure = new IllegalStateException("handshake rejected");
        var cleanupFailure = new IllegalStateException("cleanup failed");
        var closes = new CountDownLatch(1);
        var closeCount = new AtomicInteger();
        var writer = new AtomicReference<Thread>();
        var transport =
                new FrameTransport() {
                    public void send(@NonNull Frame frame) {
                        writer.set(Thread.currentThread());
                        throw handshakeFailure;
                    }

                    public @NonNull Frame receive() throws IOException {
                        await(closes);
                        throw new IOException("Transport closed");
                    }

                    public void close() {
                        closeCount.incrementAndGet();
                        closes.countDown();
                        throw cleanupFailure;
                    }
                };
        var client = new ProtocolClient(transport);
        try {
            assertNull(client.receive(2, TimeUnit.SECONDS));
            assertTrue(client.isClosed());
            client.close();
            assertWorkerStopped(writer.get());
            assertArrayEquals(new Throwable[] {cleanupFailure}, handshakeFailure.getSuppressed());
            assertEquals(1, closeCount.get());
            assertNull(client.receive());
        } finally {
            client.close();
        }
    }

    @Test
    @Timeout(5)
    void incompatibleWelcomeWrongDirectionAndEarlyApplicationDataFailTheHandshake()
            throws Exception {
        List<@NonNull Frame> invalid =
                List.of(
                        new Frame.Welcome(Frame.PROTOCOL_VERSION + 1, 1, Version.UNKNOWN),
                        new Frame.Request(1, null, null, "wrong direction"),
                        Delta.message("not negotiated"));
        for (var frame : invalid) {
            var closed = new CountDownLatch(1);
            var closes = new AtomicInteger();
            var transport =
                    new FrameTransport() {
                        public void send(@NonNull Frame ignored) {}

                        public @NonNull Frame receive() {
                            return frame;
                        }

                        public void close() {
                            closes.incrementAndGet();
                            closed.countDown();
                        }
                    };
            var client = new ProtocolClient(transport);
            try {
                assertNull(client.receive(1, TimeUnit.SECONDS));
                assertTrue(closed.await(1, TimeUnit.SECONDS));
                assertTrue(client.isClosed());
                client.close();
                assertEquals(1, closes.get());
                assertNull(client.receive());
            } finally {
                client.close();
            }
        }
    }

    @Test
    @Timeout(5)
    void backendHandshakeRejectionPreservesItsExplanation() throws Exception {
        List<@NonNull Frame> rejections =
                List.of(
                        new Frame.Error("Backend unavailable", 1),
                        new Frame.Terminate("Backend unavailable"));
        for (var rejection : rejections) {
            var closed = new CountDownLatch(1);
            var transport =
                    new FrameTransport() {
                        public void send(@NonNull Frame ignored) {}

                        public @NonNull Frame receive() {
                            return rejection;
                        }

                        public void close() {
                            closed.countDown();
                        }
                    };
            try (var client = new ProtocolClient(transport)) {
                assertTrue(rejection == client.receive(1, TimeUnit.SECONDS));
                assertTrue(closed.await(1, TimeUnit.SECONDS));
                assertNull(client.receive());
            }
        }
    }

    @Test
    @Timeout(15)
    void missingWelcomeDeadlineClosesReaderAndWriter() throws Exception {
        var closed = new CountDownLatch(1);
        var helloSent = new CountDownLatch(1);
        var reader = new AtomicReference<Thread>();
        var writer = new AtomicReference<Thread>();
        var transport =
                new FrameTransport() {
                    public void send(@NonNull Frame frame) {
                        writer.set(Thread.currentThread());
                        assertInstanceOf(Frame.Hello.class, frame);
                        helloSent.countDown();
                    }

                    public @NonNull Frame receive() throws IOException {
                        reader.set(Thread.currentThread());
                        await(closed);
                        throw new IOException("Transport closed");
                    }

                    public void close() {
                        closed.countDown();
                    }
                };
        long started = System.nanoTime();
        var client = new ProtocolClient(transport);
        try {
            assertTrue(helloSent.await(1, TimeUnit.SECONDS));
            client.send(new Frame.Request(client.nextSequence(), null, null, "queued"));
            assertNull(client.receive(12, TimeUnit.SECONDS));
            assertTrue(System.nanoTime() - started >= TimeUnit.SECONDS.toNanos(9));
            assertTrue(closed.await(1, TimeUnit.SECONDS));
            client.close();
            assertWorkerStopped(reader.get());
            assertWorkerStopped(writer.get());
            assertNull(client.receive());
        } finally {
            client.close();
        }
    }

    @Test
    @Timeout(5)
    void receiveFailureRetainsEarlierFramesThenEndsStreamAndStopsBothWorkers() throws Exception {
        var failReceive = new CountDownLatch(1);
        var helloSent = new CountDownLatch(1);
        var closed = new CountDownLatch(1);
        var reader = new AtomicReference<Thread>();
        var writer = new AtomicReference<Thread>();
        var progress = Delta.message("accepted progress");
        var transport =
                new FrameTransport() {
                    private int received;

                    public void send(@NonNull Frame frame) {
                        writer.set(Thread.currentThread());
                        helloSent.countDown();
                    }

                    public @NonNull Frame receive() throws IOException {
                        reader.set(Thread.currentThread());
                        await(helloSent);
                        if (received++ == 0)
                            return new Frame.Welcome(Frame.PROTOCOL_VERSION, 1, Version.UNKNOWN);
                        if (received == 2) return progress;
                        await(failReceive);
                        throw new IOException("receive failed");
                    }

                    public void close() {
                        closed.countDown();
                    }
                };
        var client = new ProtocolClient(transport);
        try {
            assertInstanceOf(Frame.Welcome.class, client.receive(1, TimeUnit.SECONDS));
            failReceive.countDown();
            assertTrue(closed.await(2, TimeUnit.SECONDS));
            assertTrue(client.isClosed());
            assertEquals(progress, client.receive());
            assertNull(client.receive());
            client.close();
            assertWorkerStopped(reader.get());
            assertWorkerStopped(writer.get());
        } finally {
            failReceive.countDown();
            client.close();
        }
    }

    private static void await(@NonNull CountDownLatch latch) throws IOException {
        try {
            latch.await();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException(failure);
        }
    }

    private static void assertWorkerStopped(Thread worker) throws InterruptedException {
        if (worker == null) throw new AssertionError("Missing worker");
        assertTrue(worker.isVirtual());
        worker.join(1_000);
        assertFalse(worker.isAlive());
    }
}
