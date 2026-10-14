package top.focess.veto.contract;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ProtocolClientWorkerTest {
    @Test
    @Timeout(15)
    void missingAcknowledgementClosesAndWakesPendingExchangeWithoutAnotherHeartbeat()
            throws Exception {
        var transport = new RecordingTransport();
        transport.acknowledge = false;
        try (var client = new ProtocolClient(() -> transport, Version.UNKNOWN, "", 20)) {
            long started = System.nanoTime();
            assertNull(client.hint("pending", 30, TimeUnit.SECONDS));
            assertTrue(client.isClosed());
            assertTrue(transport.closed.await(1, TimeUnit.SECONDS));
            assertEquals(
                    2, transport.heartbeats.getCount(), "Only one heartbeat may be outstanding");
            assertTrue(System.nanoTime() - started >= TimeUnit.SECONDS.toNanos(9));
            assertNull(client.receive(1, TimeUnit.SECONDS));
        }
    }

    @Test
    @Timeout(5)
    void idleConnectionSendsHeartbeatsOnItsVirtualIoOwner() throws Exception {
        exerciseWorker(false);
    }

    @Test
    @Timeout(5)
    void continuousOutboundTrafficCannotStarveHeartbeatReceiveOrClose() throws Exception {
        exerciseWorker(true);
    }

    private void exerciseWorker(boolean busy) throws Exception {
        var transport = new RecordingTransport();
        var client =
                new ProtocolClient(
                        () -> {
                            transport.recordOwner();
                            return transport;
                        },
                        Version.UNKNOWN,
                        "",
                        20);
        transport.client.set(client);
        transport.keepSending.set(busy);
        try (client) {
            if (busy) client.send(new Frame.Request(client.nextSequence(), null, null, "test"));
            assertTrue(transport.heartbeats.await(2, TimeUnit.SECONDS), "heartbeats were starved");
            assertTrue(transport.receives.get() > 0, "receive was starved");
            var owner = transport.owner.get();
            if (owner == null) throw new AssertionError("Missing IO owner");
            assertTrue(owner.isVirtual(), "a connection must not allocate a platform worker");
            assertFalse(transport.changedOwner.get(), "all IO must stay on the same worker");
            if (busy) assertTrue(transport.sends.get() > 256);
        } finally {
            transport.keepSending.set(false);
            client.close();
        }
        assertTrue(transport.closed.await(1, TimeUnit.SECONDS));
        var owner = transport.owner.get();
        if (owner == null) throw new AssertionError("Missing IO owner");
        owner.join(1_000);
        assertFalse(owner.isAlive(), "closed connection retained its worker");
        assertFalse(transport.changedOwner.get(), "cleanup must stay on the IO owner");
    }

    private static final class RecordingTransport implements ClientTransport {
        private final @NonNull BlockingQueue<Frame.@NonNull ServerFrame> incoming =
                new LinkedBlockingQueue<>();
        private final @NonNull AtomicReference<ProtocolClient> client = new AtomicReference<>();
        private final @NonNull AtomicReference<Thread> owner = new AtomicReference<>();
        private final @NonNull AtomicBoolean changedOwner = new AtomicBoolean();
        private final @NonNull AtomicBoolean keepSending = new AtomicBoolean();
        private final @NonNull AtomicInteger sends = new AtomicInteger();
        private final @NonNull AtomicInteger receives = new AtomicInteger();
        private final @NonNull CountDownLatch heartbeats = new CountDownLatch(3);
        private final @NonNull CountDownLatch closed = new CountDownLatch(1);
        private boolean acknowledge = true;

        @Override
        public void send(Frame.@NonNull ClientFrame frame) {
            recordOwner();
            if (frame instanceof Frame.Hello hello) {
                if (!incoming.offer(
                        new Frame.Welcome(Frame.PROTOCOL_VERSION, hello.seq(), Version.UNKNOWN)))
                    throw new AssertionError("Cannot enqueue welcome");
                return;
            }
            recordOwner();
            if (frame instanceof Frame.Heartbeat) {
                heartbeats.countDown();
                if (acknowledge && !incoming.offer(new Frame.HeartbeatAck(Instant.now())))
                    throw new AssertionError("Cannot acknowledge heartbeat");
            }
            if (frame instanceof Frame.Request && keepSending.get()) {
                sends.incrementAndGet();
                var active = client.get();
                if (active != null)
                    active.send(new Frame.Request(active.nextSequence(), null, null, "test"));
            }
        }

        @Override
        public Frame.ServerFrame recv(long timeoutMillis) {
            recordOwner();
            receives.incrementAndGet();
            try {
                var frame = incoming.poll(timeoutMillis, TimeUnit.MILLISECONDS);
                return frame;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }

        @Override
        public void close() {
            recordOwner();
            closed.countDown();
        }

        private void recordOwner() {
            var thread = Thread.currentThread();
            var previous = owner.get();
            if (previous == null) owner.set(thread);
            else if (previous != thread) changedOwner.set(true);
        }
    }
}
