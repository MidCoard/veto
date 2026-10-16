package top.focess.veto.contract;

import static org.junit.jupiter.api.Assertions.*;
import static top.focess.veto.contract.ContractTestSupport.assertThrows;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ProtocolClientFailureCleanupTest {
    @Test
    @Timeout(5)
    void transportFactoryFailureReachesConstructor() {
        var setupFailure = new IllegalStateException("transport setup failed");
        var failure =
                assertThrows(
                        ExecutionException.class,
                        () ->
                                new ProtocolClient(
                                        () -> {
                                            throw setupFailure;
                                        }));
        assertSame(setupFailure, assertInstanceOf(IllegalStateException.class, failure.getCause()));
    }

    @Test
    @Timeout(5)
    void rejectedHandshakeClosesTransportAndPreservesTheOriginalFailure() {
        var handshakeFailure = new IllegalStateException("handshake rejected");
        var cleanupFailure = new IllegalStateException("cleanup failed");
        var closes = new CountDownLatch(1);
        var transport =
                new FrameTransport() {
                    public void send(@NonNull Frame frame) {
                        throw handshakeFailure;
                    }

                    public @NonNull Frame receive() throws IOException {
                        await(closes);
                        throw new IOException("Transport closed");
                    }

                    public void close() {
                        closes.countDown();
                        throw cleanupFailure;
                    }
                };
        var failure =
                assertThrows(ExecutionException.class, () -> new ProtocolClient(() -> transport));
        assertSame(
                handshakeFailure,
                assertInstanceOf(IllegalStateException.class, failure.getCause()));
        assertArrayEquals(new Throwable[] {cleanupFailure}, handshakeFailure.getSuppressed());
        assertEquals(0, closes.getCount());
    }

    @Test
    @Timeout(5)
    void incompatibleWelcomeAndWrongDirectionFailTheHandshakeAndClose() {
        List<@NonNull Frame> invalid =
                List.of(
                        new Frame.Welcome(Frame.PROTOCOL_VERSION + 1, 1, Version.UNKNOWN),
                        new Frame.Request(1, null, null, "wrong direction"));
        for (var frame : invalid) {
            var closed = new CountDownLatch(1);
            var transport =
                    new FrameTransport() {
                        public void send(@NonNull Frame ignored) {}

                        public @NonNull Frame receive() {
                            return frame;
                        }

                        public void close() {
                            closed.countDown();
                        }
                    };
            var failure =
                    assertThrows(
                            ExecutionException.class, () -> new ProtocolClient(() -> transport));
            if (frame instanceof Frame.Welcome)
                assertInstanceOf(IllegalStateException.class, failure.getCause());
            else assertInstanceOf(IOException.class, failure.getCause());
            assertEquals(0, closed.getCount());
        }
    }

    @Test
    @Timeout(15)
    void handshakeDeadlineClosesBlockedReaderAndWriter() throws Exception {
        var closed = new CountDownLatch(1);
        var reader = new AtomicReference<Thread>();
        var writer = new AtomicReference<Thread>();
        var transport =
                new FrameTransport() {
                    public void send(@NonNull Frame frame) throws IOException {
                        writer.set(Thread.currentThread());
                        await(closed);
                        throw new IOException("Transport closed");
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
        var failure =
                assertThrows(ExecutionException.class, () -> new ProtocolClient(() -> transport));
        var cause = assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertEquals("Protocol handshake timed out", cause.getMessage());
        assertEquals(0, closed.getCount());
        assertWorkerStopped(reader);
        assertWorkerStopped(writer);
    }

    @Test
    @Timeout(5)
    void interruptedConstructorInterruptsTransportSetupAndPropagatesInterruption()
            throws Exception {
        var entering = new CountDownLatch(1);
        var setupWorker = new AtomicReference<Thread>();
        var interrupted = new AtomicBoolean();
        var result = new CompletableFuture<@NonNull Throwable>();
        var caller =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    try (var opened =
                                            new ProtocolClient(
                                                    () -> {
                                                        setupWorker.set(Thread.currentThread());
                                                        entering.countDown();
                                                        new CountDownLatch(1).await();
                                                        throw new AssertionError(
                                                                "Setup should be interrupted");
                                                    })) {
                                        assertFalse(opened.isClosed());
                                        result.complete(
                                                new AssertionError("Connection should not open"));
                                    } catch (Throwable failure) {
                                        interrupted.set(Thread.currentThread().isInterrupted());
                                        result.complete(failure);
                                    }
                                });
        assertTrue(entering.await(1, TimeUnit.SECONDS));
        caller.interrupt();
        assertInstanceOf(InterruptedException.class, result.get(2, TimeUnit.SECONDS));
        assertWorkerStopped(setupWorker);
        assertFalse(interrupted.get());
        caller.join(1_000);
        assertFalse(caller.isAlive());
    }

    @Test
    @Timeout(5)
    void receiveFailureClosesBothVirtualWorkers() throws Exception {
        var failReceive = new CountDownLatch(1);
        var closed = new CountDownLatch(1);
        var reader = new AtomicReference<Thread>();
        var writer = new AtomicReference<Thread>();
        var transport =
                new FrameTransport() {
                    private boolean welcomed;

                    public void send(@NonNull Frame frame) {
                        writer.set(Thread.currentThread());
                    }

                    public @NonNull Frame receive() throws IOException {
                        reader.set(Thread.currentThread());
                        if (!welcomed) {
                            welcomed = true;
                            return new Frame.Welcome(Frame.PROTOCOL_VERSION, 1, Version.UNKNOWN);
                        }
                        await(failReceive);
                        throw new IOException("receive failed");
                    }

                    public void close() {
                        closed.countDown();
                    }
                };
        try (var client = new ProtocolClient(() -> transport)) {
            failReceive.countDown();
            assertTrue(closed.await(2, TimeUnit.SECONDS));
            assertTrue(client.isClosed());
            assertWorkerStopped(reader);
            assertWorkerStopped(writer);
        } finally {
            failReceive.countDown();
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

    private static void assertWorkerStopped(@NonNull AtomicReference<Thread> reference)
            throws InterruptedException {
        var worker = reference.get();
        if (worker == null) throw new AssertionError("Missing worker");
        assertTrue(worker.isVirtual());
        worker.join(1_000);
        assertFalse(worker.isAlive());
    }
}
