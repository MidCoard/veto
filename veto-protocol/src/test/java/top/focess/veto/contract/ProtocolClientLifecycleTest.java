package top.focess.veto.contract;

import static org.junit.jupiter.api.Assertions.*;
import static top.focess.veto.contract.ContractTestSupport.assertThrows;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import top.focess.veto.api.plugin.Scope;

@Timeout(10)
class ProtocolClientLifecycleTest {
    @Test
    void interruptedQueryPreservesThePromptWakeSignalWithoutClosingTheConnection()
            throws Exception {
        var peer = new Peer();
        try (var client = new ProtocolClient(() -> peer)) {
            var answer = new CompletableFuture<Frame.@Nullable HintResult>();
            var interrupted = new AtomicBoolean();
            var requester =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        var result = client.hint("command", 30, TimeUnit.SECONDS);
                                        interrupted.set(Thread.currentThread().isInterrupted());
                                        answer.complete(result);
                                    });
            var request = assertInstanceOf(Frame.Hint.class, peer.sent.poll(1, TimeUnit.SECONDS));
            requester.interrupt();
            assertNull(answer.get(1, TimeUnit.SECONDS));
            requester.join(1_000);
            assertFalse(requester.isAlive());
            assertTrue(interrupted.get(), "JLine must still observe the prompt-swap wake signal");
            assertFalse(client.isClosed());
            var late = new Frame.HintResult(new Frame.HintInfo("argument", null), request.seq());
            assertTrue(peer.incoming.offer(late));
            assertEquals(late, client.receive(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void cancellationUsesTheConnectionSequenceAndDoesNotConsumeQueryReplies() throws Exception {
        var peer = new Peer();
        try (var client = new ProtocolClient(() -> peer)) {
            long operation = client.nextSequence();
            client.cancel(operation);
            var cancel = assertInstanceOf(Frame.Cancel.class, peer.sent.poll(1, TimeUnit.SECONDS));
            assertEquals(operation, cancel.targetSeq());
            assertTrue(cancel.seq() > operation);
            var answer = new CompletableFuture<Frame.@Nullable HintResult>();
            Thread.ofVirtual()
                    .start(() -> answer.complete(client.hint("command", 5, TimeUnit.SECONDS)));
            var hint = assertInstanceOf(Frame.Hint.class, peer.sent.poll(1, TimeUnit.SECONDS));
            assertTrue(hint.seq() > cancel.seq());
            var result = new Frame.CancelResult(Frame.CancelStatus.ACCEPTED, cancel.seq());
            assertTrue(peer.incoming.offer(result));
            assertEquals(result, client.receive(1, TimeUnit.SECONDS));
            assertFalse(answer.isDone());
            var reply = new Frame.HintResult(new Frame.HintInfo("hint", null), hint.seq());
            assertTrue(peer.incoming.offer(reply));
            assertEquals(reply, answer.get(1, TimeUnit.SECONDS));
            client.cancel(2);
            var next = assertInstanceOf(Frame.Cancel.class, peer.sent.poll(1, TimeUnit.SECONDS));
            assertTrue(next.seq() > hint.seq());
        }
    }

    @Test
    void serverRequestDoesNotConsumeClientExchangeWithTheSameSequence() throws Exception {
        var peer = new Peer();
        try (var client = new ProtocolClient(() -> peer)) {
            var answer = new CompletableFuture<Frame.@Nullable HintResult>();
            var worker =
                    Thread.ofVirtual()
                            .start(
                                    () ->
                                            answer.complete(
                                                    client.hint("command", 5, TimeUnit.SECONDS)));
            var sent = peer.sent.poll(1, TimeUnit.SECONDS);
            if (!(sent instanceof Frame.Hint hint))
                throw new AssertionError("Expected hint request");
            var scope =
                    new Scope.AgentScope(UUID.randomUUID(), UUID.randomUUID().toString(), "agent");
            var request =
                    new Frame.Veto(
                            scope,
                            "call",
                            "tool",
                            "GENERIC",
                            List.of("DECLINE"),
                            Map.of(),
                            null,
                            null,
                            hint.seq());
            assertTrue(peer.incoming.offer(request));
            assertEquals(request, client.receive(1, TimeUnit.SECONDS));
            assertFalse(answer.isDone(), "Incoming requests must not resolve outgoing requests");
            var reply = new Frame.VetoResult(scope, "call", "DECLINE", request.seq());
            client.send(reply);
            assertEquals(reply, peer.sent.poll(1, TimeUnit.SECONDS));
            var expected = new Frame.HintResult(new Frame.HintInfo("argument", null), hint.seq());
            assertTrue(peer.incoming.offer(expected));
            assertEquals(expected, answer.get(1, TimeUnit.SECONDS));
            worker.join(1000);
            assertFalse(worker.isAlive());
        }
    }

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
            var stream = new CompletableFuture<@Nullable Frame>();
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
                                            stream.complete(client.receive());
                                        } catch (Throwable failure) {
                                            stream.completeExceptionally(failure);
                                        }
                                    });
            var request = peer.sent.poll(1, TimeUnit.SECONDS);
            assertTrue(request instanceof Frame.Hint frame && frame.seq() > 1);
            var unsent = new Frame.Request(client.nextSequence(), null, null, "late");
            client.close();
            assertNull(hint.get(1, TimeUnit.SECONDS));
            assertNull(stream.get(1, TimeUnit.SECONDS));
            requester.join(1_000);
            reader.join(1_000);
            assertFalse(requester.isAlive());
            assertFalse(reader.isAlive());
            assertThrows(IllegalStateException.class, () -> client.send(unsent));
            assertThrows(IllegalStateException.class, client::nextSequence);
        } finally {
            client.close();
        }
    }

    @Test
    void blockingReceiveDeliversTheNextFrame() throws Exception {
        var peer = new Peer();
        try (var client = new ProtocolClient(() -> peer)) {
            var waiting = new CountDownLatch(1);
            var received = new CompletableFuture<Frame.@Nullable ServerFrame>();
            var consumer =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        waiting.countDown();
                                        try {
                                            received.complete(client.receive());
                                        } catch (Throwable failure) {
                                            received.completeExceptionally(failure);
                                        }
                                    });
            assertTrue(waiting.await(1, TimeUnit.SECONDS));
            assertFalse(received.isDone());
            var frame = Delta.message("progress");
            assertTrue(peer.incoming.offer(frame));
            assertEquals(frame, received.get(1, TimeUnit.SECONDS));
            consumer.join(1_000);
            assertFalse(consumer.isAlive());
        }
    }

    @Test
    void fullOutboxRejectsWorkInsteadOfDroppingIt() throws Exception {
        var peer = new Peer();
        peer.blockSend = true;
        try (var client = new ProtocolClient(() -> peer)) {
            client.send(new Frame.Request(client.nextSequence(), null, null, "in-flight"));
            assertTrue(peer.sending.await(1, TimeUnit.SECONDS));
            for (int i = 0; i < 256; i++)
                client.send(new Frame.Request(client.nextSequence(), null, null, "queued"));
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            client.send(
                                    new Frame.Request(
                                            client.nextSequence(), null, null, "overflow")));
            peer.release.countDown();
        } finally {
            peer.release.countDown();
        }
    }

    @Test
    void outboxRejectsExcessBytesBeforeItsFrameCountLimit() throws Exception {
        var peer = new Peer();
        peer.blockSend = true;
        try (var client = new ProtocolClient(() -> peer)) {
            client.send(new Frame.Request(client.nextSequence(), null, null, "in-flight"));
            assertTrue(peer.sending.await(1, TimeUnit.SECONDS));
            var payload = "x".repeat(800 * 1024);
            for (int i = 0; i < 10; i++)
                client.send(new Frame.Request(client.nextSequence(), null, null, payload));
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            client.send(
                                    new Frame.Request(client.nextSequence(), null, null, payload)));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            client.send(
                                    new Frame.Request(
                                            client.nextSequence(),
                                            null,
                                            null,
                                            "x".repeat(FrameTransport.MAX_FRAME_BYTES))));
        }
    }

    @Test
    void blockedWriterDoesNotStopRepliesOrStreamDelivery() throws Exception {
        var peer = new Peer();
        try (var client = new ProtocolClient(() -> peer)) {
            var answer = new CompletableFuture<Frame.@Nullable HintResult>();
            Thread.ofVirtual()
                    .start(() -> answer.complete(client.hint("command", 5, TimeUnit.SECONDS)));
            var request = assertInstanceOf(Frame.Hint.class, peer.sent.poll(1, TimeUnit.SECONDS));
            peer.blockSend = true;
            client.send(new Frame.Request(client.nextSequence(), null, null, "blocked"));
            assertTrue(peer.sending.await(1, TimeUnit.SECONDS));
            var reply = new Frame.HintResult(new Frame.HintInfo("argument", null), request.seq());
            assertTrue(peer.incoming.offer(reply));
            assertTrue(peer.incoming.offer(Delta.message("progress")));
            assertEquals(reply, answer.get(1, TimeUnit.SECONDS));
            assertInstanceOf(Delta.class, client.receive(1, TimeUnit.SECONDS));
            assertEquals(1, peer.release.getCount(), "Writer must still be blocked");
        }
    }

    @Test
    void receiveOverflowClosesConnectionAndRetainsAcceptedFrames() throws Exception {
        var peer = new Peer();
        try (var client = new ProtocolClient(() -> peer)) {
            for (int i = 0; i < 257; i++) assertTrue(peer.incoming.offer(Delta.message("event")));
            assertTrue(peer.closed.await(2, TimeUnit.SECONDS));
            assertTrue(client.isClosed());
            for (int i = 0; i < 256; i++) assertTrue(client.receive() instanceof Delta);
            assertNull(client.receive());
        }
    }

    @Test
    void incomingByteBudgetClosesBeforeTheFrameCountLimit() throws Exception {
        var peer = new Peer();
        try (var client = new ProtocolClient(() -> peer)) {
            var frame = Delta.message("x".repeat(800 * 1024));
            for (int i = 0; i < 11; i++) assertTrue(peer.incoming.offer(frame));
            assertTrue(peer.closed.await(2, TimeUnit.SECONDS));
            assertTrue(client.isClosed());
            for (int i = 0; i < 10; i++) assertEquals(frame, client.receive(0, TimeUnit.SECONDS));
            assertNull(client.receive(0, TimeUnit.SECONDS));
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
            var inFlight = new Frame.Request(client.nextSequence(), null, null, "in-flight");
            client.send(inFlight);
            assertTrue(peer.sending.await(1, TimeUnit.SECONDS));
            for (int i = 0; i < 100; i++)
                client.send(new Frame.Request(client.nextSequence(), null, null, "unsent"));
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
            closing.get(2, TimeUnit.SECONDS);
            assertTrue(peer.closed.await(1, TimeUnit.SECONDS));
            assertTrue(peer.sent.isEmpty(), "close must not drain unsent application frames");
        } finally {
            peer.release.countDown();
            client.close();
        }
    }

    private static final class Peer implements FrameTransport {
        private static final Frame.@NonNull ServerFrame END = new Frame.HeartbeatAck(Instant.EPOCH);
        private final @NonNull BlockingQueue<Frame.@NonNull ServerFrame> incoming =
                new LinkedBlockingQueue<>();
        private final @NonNull BlockingQueue<Frame.@NonNull ClientFrame> sent =
                new LinkedBlockingQueue<>();
        private final @NonNull CountDownLatch closed = new CountDownLatch(1);
        private final @NonNull CountDownLatch release = new CountDownLatch(1);
        private volatile boolean blockSend;
        private final @NonNull CountDownLatch sending = new CountDownLatch(1);

        @Override
        public void send(@NonNull Frame frame) throws IOException {
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
                        throw new IOException(interrupted);
                    }
                }
                if (closed.getCount() == 0) throw new IOException("Transport closed");
                if (!(frame instanceof Frame.ClientFrame request) || !sent.offer(request))
                    throw new AssertionError("Cannot record send");
            }
        }

        @Override
        public Frame.@NonNull ServerFrame receive() throws IOException {
            try {
                var frame = incoming.take();
                if (frame == END) throw new IOException("Transport closed");
                return frame;
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IOException(failure);
            }
        }

        @Override
        public void close() {
            closed.countDown();
            release.countDown();
            if (!incoming.offer(END)) throw new AssertionError("Cannot wake reader");
        }
    }
}
