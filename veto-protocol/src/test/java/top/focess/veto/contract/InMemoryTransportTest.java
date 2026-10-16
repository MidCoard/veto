package top.focess.veto.contract;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.util.Threads;

/**
 * Drives {@link ProtocolClient} end-to-end over an in-memory {@link FrameTransport}. A responder
 * thread plays the backend: it answers Hello (via the transport), Complete, Hint, and Request
 * frames so the connection's handshake, seq correlation, streaming receive, and close paths are
 * exercised without a socket.
 */
@SuppressWarnings("initialization.field.uninitialized")
class InMemoryTransportTest {

    private @NonNull InMemoryTransport transport;
    private @NonNull ProtocolClient conn;
    private @NonNull Thread responder;

    @BeforeEach
    void setUp() throws Exception {
        transport = new InMemoryTransport();
        conn = new ProtocolClient(() -> transport);
        responder =
                Thread.ofVirtual()
                        .name("inmem-responder")
                        .start(
                                () -> {
                                    try {
                                        while (true) respond(transport.sent.take());
                                    } catch (InterruptedException stopped) {
                                        // Teardown interrupts the blocking fixture queue to end
                                        // this worker.
                                    }
                                });
    }

    @AfterEach
    void tearDown() {
        responder.interrupt();
        conn.close();
        Threads.awaitTermination(responder, 1_000);
    }

    private void respond(@NonNull Frame frame) {
        if (frame instanceof Frame.Hello) {
            // The transport already auto-replied with Welcome in send(); nothing more to do.
            return;
        }
        if (frame instanceof Frame.Complete c) {
            transport.deliver(
                    new Frame.CompleteResult(
                            List.of(new Frame.Completion("/login", "sign in", "auth")), c.seq()));
        } else if (frame instanceof Frame.Hint h) {
            transport.deliver(new Frame.HintResult(new Frame.HintInfo("<user>", null), h.seq()));
        } else if (frame instanceof Frame.Request request) {
            transport.deliver(
                    new Frame.RequestAccepted(request.seq(), new Scope.GlobalScope(), null));
            transport.deliver(Delta.message("hello "));
            transport.deliver(Delta.message("world"));
            transport.deliver(
                    new Frame.Done(new Scope.GlobalScope(), null, -1, null, null, request.seq()));
        }
        // Heartbeat / Bye / Cancel / Input: ignored by the responder.
    }

    @Test
    void handshakeNegotiatesVersion() {
        assertEquals(Frame.PROTOCOL_VERSION, conn.negotiatedVersion());
        assertFalse(conn.isClosed());
    }

    @Test
    void completeReturnsCandidates() {
        Frame.CompleteResult result =
                requireValue(
                        conn.complete("/log", 2, TimeUnit.SECONDS),
                        "complete result should not be null");
        assertNotNull(result);
        assertEquals(1, result.candidates().size());
        assertEquals("/login", result.candidates().get(0).value());
    }

    @Test
    void hintReturnsPlaceholder() {
        Frame.HintResult result =
                requireValue(
                        conn.hint("/login ", 2, TimeUnit.SECONDS),
                        "hint result should not be null");
        assertNotNull(result);
        assertEquals("<user>", result.hint().placeholder());
    }

    @Test
    void streamingRequestDeliversDeltaThenDone() throws InterruptedException {
        long requestSeq = conn.nextSequence();
        conn.send(new Frame.Request(requestSeq, null, null, "do something"));
        var accepted =
                assertInstanceOf(Frame.RequestAccepted.class, conn.receive(2, TimeUnit.SECONDS));
        assertEquals(requestSeq, accepted.seq());
        // Admission is separate from streaming and final settlement.
        Frame f1 =
                requireValue(
                        conn.receive(2, TimeUnit.SECONDS), "first server frame should not be null");
        Frame f2 =
                requireValue(
                        conn.receive(2, TimeUnit.SECONDS),
                        "second server frame should not be null");
        Frame f3 =
                requireValue(
                        conn.receive(2, TimeUnit.SECONDS), "third server frame should not be null");
        assertInstanceOf(Objects.requireNonNull(Delta.class), f1);
        assertInstanceOf(Objects.requireNonNull(Delta.class), f2);
        assertEquals(
                requestSeq, assertInstanceOf(Objects.requireNonNull(Frame.Done.class), f3).seq());
    }

    @Test
    void closeClosesTheTransportAndWakesItsReader() throws InterruptedException {
        responder.interrupt();
        responder.join(1_000);
        conn.close();
        assertTrue(conn.isClosed());
        assertTrue(transport.closed.await(1, TimeUnit.SECONDS));
        assertNull(conn.receive(0, TimeUnit.SECONDS));
    }

    private static <T extends Object> @NonNull T requireValue(T value, String message) {
        if (value != null) {
            return value;
        }
        throw new AssertionError(message);
    }

    /** Minimal in-memory transport that auto-replies to Hello with Welcome. */
    static final class InMemoryTransport implements FrameTransport {
        private static final Frame.@NonNull ServerFrame END = new Frame.HeartbeatAck(Instant.EPOCH);
        final @NonNull BlockingQueue<@NonNull Frame> sent = new LinkedBlockingQueue<>();
        final @NonNull BlockingQueue<Frame.@NonNull ServerFrame> inbox =
                new LinkedBlockingQueue<>();
        final @NonNull CountDownLatch closed = new CountDownLatch(1);

        @Override
        public void send(@NonNull Frame frame) {
            if (!sent.offer(frame)) throw new AssertionError("Cannot record send");
            if (frame instanceof Frame.Hello h) {
                deliver(new Frame.Welcome(Frame.PROTOCOL_VERSION, h.seq(), Version.UNKNOWN));
            }
        }

        @Override
        public Frame.@NonNull ServerFrame receive() throws IOException {
            try {
                var frame = inbox.take();
                if (frame == END) throw new IOException("Transport closed");
                return frame;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        }

        @Override
        public void close() {
            closed.countDown();
            deliver(END);
        }

        void deliver(Frame.@NonNull ServerFrame frame) {
            if (!inbox.offer(frame)) throw new AssertionError("Cannot deliver frame");
        }
    }
}
