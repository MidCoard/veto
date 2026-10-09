package top.focess.veto.contract;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Drives {@link ProtocolClient} end-to-end over an in-memory {@link ClientTransport} — no ZMQ. A
 * responder thread plays the backend: it answers Hello (via the transport), Complete, Hint, and
 * Request frames so the connection's handshake, seq correlation, streaming receive, and
 * flush-on-close paths are exercised without a socket.
 */
@SuppressWarnings("initialization.field.uninitialized")
class InMemoryTransportTest {

    private @NonNull InMemoryTransport transport;
    private @NonNull ProtocolClient conn;
    private @NonNull Thread responder;
    private volatile boolean responderRunning;

    @BeforeEach
    void setUp() {
        transport = new InMemoryTransport();
        conn = new ProtocolClient(() -> transport);
        responderRunning = true;
        responder =
                new Thread(
                        () -> {
                            while (responderRunning) {
                                Frame.ClientFrame frame;
                                try {
                                    frame = transport.sent.poll(50, TimeUnit.MILLISECONDS);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    return;
                                }
                                if (frame == null) continue;
                                respond(frame);
                            }
                        },
                        "inmem-responder");
        responder.setDaemon(true);
        responder.start();
    }

    @AfterEach
    void tearDown() {
        responderRunning = false;
        responder.interrupt();
        conn.close();
    }

    private void respond(Frame.@NonNull ClientFrame frame) {
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
        } else if (frame instanceof Frame.Request) {
            transport.deliver(EventFrame.command("hello "));
            transport.deliver(EventFrame.command("world"));
            transport.deliver(new Frame.Done(Map.of(), null));
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
        conn.send(new Frame.Request("do something"));
        // The responder emits Delta, Delta, Done. Receive them in order from the incoming queue.
        Frame.ServerFrame f1 =
                requireValue(
                        conn.receive(2, TimeUnit.SECONDS), "first server frame should not be null");
        Frame.ServerFrame f2 =
                requireValue(
                        conn.receive(2, TimeUnit.SECONDS),
                        "second server frame should not be null");
        Frame.ServerFrame f3 =
                requireValue(
                        conn.receive(2, TimeUnit.SECONDS), "third server frame should not be null");
        assertInstanceOf(Objects.requireNonNull(EventFrame.class), f1);
        assertInstanceOf(Objects.requireNonNull(EventFrame.class), f2);
        assertInstanceOf(Objects.requireNonNull(Frame.Done.class), f3);
    }

    @Test
    void closeFlushesBye() throws InterruptedException {
        // Stop the responder first so it does not consume the Bye before we inspect `sent`.
        responderRunning = false;
        responder.interrupt();
        responder.join(1_000);
        conn.close();
        assertTrue(conn.isClosed());
        // Drain what was sent; the Bye must have been flushed by the IO loop's final drain.
        List<Frame.@NonNull ClientFrame> sent = new ArrayList<>();
        transport.sent.drainTo(sent);
        assertTrue(
                sent.stream().anyMatch(f -> f instanceof Frame.Bye),
                "close() must flush a Bye frame, got: " + sent);
    }

    private static <T extends Object> @NonNull T requireValue(T value, String message) {
        if (value != null) {
            return value;
        }
        throw new AssertionError(message);
    }

    /** Minimal in-memory {@link ClientTransport} that auto-replies to Hello with Welcome. */
    static final class InMemoryTransport implements ClientTransport {
        final @NonNull BlockingQueue<Frame.@NonNull ClientFrame> sent = new LinkedBlockingQueue<>();
        final @NonNull BlockingQueue<Frame.@NonNull ServerFrame> inbox =
                new LinkedBlockingQueue<>();

        @Override
        public void send(Frame.@NonNull ClientFrame frame) {
            sent.offer(frame);
            if (frame instanceof Frame.Hello h) {
                inbox.offer(new Frame.Welcome(Frame.PROTOCOL_VERSION, h.seq(), Version.UNKNOWN));
            }
        }

        @Override
        public Frame.ServerFrame recv(long timeoutMillis) {
            try {
                return inbox.poll(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        @Override
        public void close() {}

        void deliver(Frame.@NonNull ServerFrame frame) {
            inbox.offer(frame);
        }
    }
}
