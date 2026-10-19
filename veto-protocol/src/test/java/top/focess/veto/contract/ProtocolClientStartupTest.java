package top.focess.veto.contract;

import static org.junit.jupiter.api.Assertions.*;
import static top.focess.veto.contract.ContractTestSupport.assertInstanceOf;
import static top.focess.veto.contract.ContractTestSupport.assertThrows;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ProtocolClientStartupTest {
    @Test
    @Timeout(5)
    void queuedRequestsWaitForTheMatchingWelcome() throws Exception {
        var transport = new HandshakeTransport();
        var productVersion = Version.parse("1.2.3");
        try (var client = new ProtocolClient(transport, productVersion, "workspace", 30_000)) {
            assertFalse(client.isClosed());
            var request = new Frame.Request(client.nextSequence(), null, null, "early request");
            client.send(request);
            assertNull(client.complete("/help", 30, TimeUnit.SECONDS));
            assertNull(client.hint("/help", 30, TimeUnit.SECONDS));
            var hello =
                    assertInstanceOf(Frame.Hello.class, transport.sent.poll(1, TimeUnit.SECONDS));
            assertEquals(1, hello.seq());
            assertEquals(Frame.PROTOCOL_VERSION, hello.version());
            assertEquals(productVersion, hello.productVersion());
            assertEquals("workspace", hello.cwd());
            assertNull(transport.sent.poll(100, TimeUnit.MILLISECONDS));
            transport.deliver(
                    new Frame.Welcome(
                            Frame.PROTOCOL_VERSION + 1, hello.seq() + 1, Version.UNKNOWN));
            assertNull(
                    client.receive(100, TimeUnit.MILLISECONDS),
                    "An uncorrelated Welcome is ignored");
            assertNull(transport.sent.poll(100, TimeUnit.MILLISECONDS));
            var welcome = new Frame.Welcome(Frame.PROTOCOL_VERSION, hello.seq(), productVersion);
            var progress = Delta.message("after negotiation");
            transport.deliver(welcome);
            transport.deliver(progress);
            assertEquals(welcome, client.receive(1, TimeUnit.SECONDS));
            assertEquals(progress, client.receive(1, TimeUnit.SECONDS));
            assertEquals(request, transport.sent.poll(1, TimeUnit.SECONDS));
        }
    }

    @Test
    @Timeout(5)
    void queuedRequestsRemainBoundedWhileAwaitingWelcome() throws Exception {
        var transport = new HandshakeTransport();
        try (var client = new ProtocolClient(transport)) {
            assertInstanceOf(Frame.Hello.class, transport.sent.poll(1, TimeUnit.SECONDS));
            for (int i = 0; i < 256; i++)
                client.send(new Frame.Request(client.nextSequence(), null, null, "queued"));
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            client.send(
                                    new Frame.Request(
                                            client.nextSequence(), null, null, "overflow")));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            client.send(
                                    new Frame.Request(
                                            client.nextSequence(),
                                            null,
                                            null,
                                            "x".repeat(FrameTransport.MAX_FRAME_BYTES))));
            assertTrue(transport.sent.isEmpty());
            assertFalse(client.isClosed(), "Admission rejection must not abort the connection");
        }
    }

    @Test
    @Timeout(18)
    void lateWelcomeFailsEvenWhileTheHelloWriteIsBlocked() throws Exception {
        var transport = new HandshakeTransport();
        transport.blockHello = true;
        var client = new ProtocolClient(transport);
        try {
            assertTrue(transport.helloStarted.await(1, TimeUnit.SECONDS));
            assertFalse(
                    transport.closed.await(11, TimeUnit.SECONDS),
                    "The transport owns its write bound; no extra client timeout worker runs");
            transport.deliver(new Frame.Welcome(Frame.PROTOCOL_VERSION, 1, Version.UNKNOWN));
            assertNull(client.receive(1, TimeUnit.SECONDS));
            assertTrue(transport.closed.await(1, TimeUnit.SECONDS));
            client.close();
            var writer = transport.writer.get();
            if (writer == null) throw new AssertionError("Missing writer");
            writer.join(1_000);
            assertFalse(writer.isAlive(), "Transport close must release the blocked Hello write");
            assertInstanceOf(Frame.Hello.class, transport.sent.poll());
            assertTrue(transport.sent.isEmpty());
            assertNull(client.receive());
        } finally {
            client.close();
        }
    }

    private static final class HandshakeTransport implements FrameTransport {
        private static final @NonNull Frame END = new Frame.HeartbeatAck(Instant.EPOCH);
        private final @NonNull BlockingQueue<@NonNull Frame> incoming = new LinkedBlockingQueue<>();
        private final @NonNull BlockingQueue<@NonNull Frame> sent = new LinkedBlockingQueue<>();
        private final @NonNull CountDownLatch helloStarted = new CountDownLatch(1);
        private final @NonNull CountDownLatch closed = new CountDownLatch(1);
        private final @NonNull AtomicReference<Thread> writer = new AtomicReference<>();
        private boolean blockHello;

        public void send(@NonNull Frame frame) throws IOException {
            writer.set(Thread.currentThread());
            if (!sent.offer(frame)) throw new AssertionError("Cannot record sent frame");
            if (frame instanceof Frame.Hello) {
                helloStarted.countDown();
                if (blockHello) {
                    try {
                        if (!closed.await(15, TimeUnit.SECONDS))
                            throw new IOException("Simulated write timed out");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException(interrupted);
                    }
                    throw new IOException("Transport closed");
                }
            }
        }

        public @NonNull Frame receive() throws IOException {
            try {
                var frame = incoming.take();
                if (frame == END) throw new IOException("Transport closed");
                return frame;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
            }
        }

        private void deliver(@NonNull Frame frame) {
            if (!incoming.offer(frame)) throw new AssertionError("Cannot deliver frame");
        }

        public void close() {
            closed.countDown();
            deliver(END);
        }
    }
}
