package top.focess.veto.contract;

import static org.junit.jupiter.api.Assertions.*;
import static top.focess.veto.contract.ContractTestSupport.assertThrows;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class ProtocolClientLifecycleTest {
    @Test
    void unregisteredSequencedRepliesRemainVisibleToTheCaller() throws Exception {
        var peer = new Peer();
        try (var client = new ProtocolClient(() -> peer)) {
            client.send(new Frame.Hint("command", 909));
            assertTrue(
                    peer.sent.poll(1, TimeUnit.SECONDS) instanceof Frame.Hint hint
                            && hint.seq() == 909);
            var reply = new Frame.HintResult(new Frame.HintInfo("argument", null), 909);
            assertTrue(peer.incoming.offer(reply));
            assertEquals(reply, client.receive(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void closeWakesBothStreamReadersAndPendingExchanges() throws Exception {
        var peer = new Peer();
        var client = new ProtocolClient(() -> peer);
        try {
            var hint = new CompletableFuture<Frame.@Nullable HintResult>();
            var stream = new CompletableFuture<Frame.@Nullable ServerFrame>();
            var requester =
                    Thread.ofVirtual()
                            .start(
                                    () ->
                                            hint.complete(
                                                    client.hint("command", 30, TimeUnit.SECONDS)));
            var reader =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        try {
                                            stream.complete(client.receive(30, TimeUnit.SECONDS));
                                        } catch (InterruptedException failure) {
                                            Thread.currentThread().interrupt();
                                            stream.completeExceptionally(failure);
                                        }
                                    });
            var request = peer.sent.poll(1, TimeUnit.SECONDS);
            assertTrue(request instanceof Frame.Hint frame && frame.seq() > 1);
            client.close();
            assertNull(hint.get(1, TimeUnit.SECONDS));
            assertNull(stream.get(1, TimeUnit.SECONDS));
            requester.join(1_000);
            reader.join(1_000);
            assertFalse(requester.isAlive());
            assertFalse(reader.isAlive());
            assertThrows(IllegalStateException.class, () -> client.send(new Frame.Request("late")));
        } finally {
            client.close();
        }
    }

    @Test
    void fullOutboxRejectsWorkInsteadOfDroppingIt() throws Exception {
        var peer = new Peer();
        peer.block = true;
        try (var client = new ProtocolClient(() -> peer)) {
            assertTrue(peer.receiving.await(1, TimeUnit.SECONDS));
            for (int i = 0; i < 256; i++) client.send(new Frame.Request("queued"));
            assertThrows(
                    IllegalStateException.class, () -> client.send(new Frame.Request("overflow")));
            peer.release.countDown();
        } finally {
            peer.release.countDown();
        }
    }

    @Test
    void receiveOverflowClosesConnectionAndRetainsAcceptedFrames() throws Exception {
        var peer = new Peer();
        try (var client = new ProtocolClient(() -> peer)) {
            for (int i = 0; i < 257; i++)
                assertTrue(peer.incoming.offer(EventFrame.command("event")));
            assertTrue(peer.closed.await(2, TimeUnit.SECONDS));
            assertTrue(client.isClosed());
            for (int i = 0; i < 256; i++)
                assertTrue(client.receive(0, TimeUnit.MILLISECONDS) instanceof EventFrame);
            assertNull(client.receive(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void closeFailsPendingExchangesBeforeBlockedSendReturnsAndDiscardsUnsentFrames()
            throws Exception {
        var peer = new Peer();
        var client = new ProtocolClient(() -> peer);
        try {
            var hint = new CompletableFuture<Frame.@Nullable HintResult>();
            Thread.ofVirtual()
                    .start(() -> hint.complete(client.hint("pending", 30, TimeUnit.SECONDS)));
            assertTrue(peer.sent.poll(1, TimeUnit.SECONDS) instanceof Frame.Hint);
            peer.blockSend = true;
            client.send(new Frame.Request("in-flight"));
            assertTrue(peer.sending.await(1, TimeUnit.SECONDS));
            for (int i = 0; i < 100; i++) client.send(new Frame.Request("unsent"));
            var closing = new CompletableFuture<Void>();
            Thread.ofVirtual()
                    .start(
                            () -> {
                                client.close();
                                closing.complete(null);
                            });
            assertNull(
                    hint.get(1, TimeUnit.SECONDS),
                    "pending exchange must wake before socket send finishes");
            assertTrue(client.isClosed());
            peer.release.countDown();
            closing.get(2, TimeUnit.SECONDS);
            assertTrue(peer.closed.await(1, TimeUnit.SECONDS));
            assertEquals(new Frame.Request("in-flight"), peer.sent.poll());
            assertTrue(peer.sent.poll() instanceof Frame.Bye);
            assertTrue(peer.sent.isEmpty(), "close must not drain unsent application frames");
        } finally {
            peer.release.countDown();
            client.close();
        }
    }

    private static final class Peer implements ClientTransport {
        private final @NonNull BlockingQueue<Frame.@NonNull ServerFrame> incoming =
                new LinkedBlockingQueue<>();
        private final @NonNull BlockingQueue<Frame.@NonNull ClientFrame> sent =
                new LinkedBlockingQueue<>();
        private final @NonNull CountDownLatch closed = new CountDownLatch(1);
        private final @NonNull CountDownLatch receiving = new CountDownLatch(1);
        private final @NonNull CountDownLatch release = new CountDownLatch(1);
        private boolean welcomed;
        private boolean block;
        private volatile boolean blockSend;
        private final @NonNull CountDownLatch sending = new CountDownLatch(1);

        @Override
        public void send(Frame.@NonNull ClientFrame frame) {
            if (frame instanceof Frame.Hello hello) {
                if (!incoming.offer(
                        new Frame.Welcome(Frame.PROTOCOL_VERSION, hello.seq(), Version.UNKNOWN)))
                    throw new AssertionError("Cannot queue welcome");
            } else {
                if (frame instanceof Frame.Request && blockSend) {
                    sending.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                }
                if (!sent.offer(frame)) throw new AssertionError("Cannot record send");
            }
        }

        @Override
        public Frame.ServerFrame recv(long timeoutMillis) {
            try {
                if (welcomed && block) {
                    receiving.countDown();
                    release.await();
                }
                var frame = incoming.poll(timeoutMillis, TimeUnit.MILLISECONDS);
                welcomed = true;
                return frame;
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            }
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }
}
