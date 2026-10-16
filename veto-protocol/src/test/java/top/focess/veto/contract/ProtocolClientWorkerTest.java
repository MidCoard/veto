package top.focess.veto.contract;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
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
    void idleConnectionUsesIndependentVirtualReadAndWriteWorkers() throws Exception {
        exerciseWorkers(false);
    }

    @Test
    @Timeout(5)
    void continuousOutboundTrafficCannotStarveHeartbeatReceiveOrClose() throws Exception {
        exerciseWorkers(true);
    }

    private void exerciseWorkers(boolean busy) throws Exception {
        var transport = new RecordingTransport();
        var client = new ProtocolClient(() -> transport, Version.UNKNOWN, "", 20);
        transport.client.set(client);
        transport.keepSending.set(busy);
        try (client) {
            if (busy) client.send(new Frame.Request(client.nextSequence(), null, null, "test"));
            assertTrue(transport.heartbeats.await(2, TimeUnit.SECONDS), "heartbeats were starved");
            var reader = transport.reader.get();
            var writer = transport.writer.get();
            if (reader == null || writer == null) throw new AssertionError("Missing IO worker");
            assertTrue(reader.isVirtual());
            assertTrue(writer.isVirtual());
            assertNotSame(reader, writer);
            assertFalse(transport.changedOwner.get(), "Each IO direction needs one owner");
            if (busy) assertTrue(transport.sends.get() > 0);
        } finally {
            transport.keepSending.set(false);
            client.close();
        }
        assertTrue(transport.closed.await(1, TimeUnit.SECONDS));
        var reader = transport.reader.get();
        var writer = transport.writer.get();
        if (reader == null || writer == null) throw new AssertionError("Missing IO worker");
        reader.join(1_000);
        writer.join(1_000);
        assertFalse(reader.isAlive());
        assertFalse(writer.isAlive());
    }

    private static final class RecordingTransport implements FrameTransport {
        private static final Frame.@NonNull ServerFrame END = new Frame.HeartbeatAck(Instant.EPOCH);
        private final @NonNull BlockingQueue<Frame.@NonNull ServerFrame> incoming =
                new LinkedBlockingQueue<>();
        private final @NonNull AtomicReference<ProtocolClient> client = new AtomicReference<>();
        private final @NonNull AtomicReference<Thread> reader = new AtomicReference<>();
        private final @NonNull AtomicReference<Thread> writer = new AtomicReference<>();
        private final @NonNull AtomicBoolean changedOwner = new AtomicBoolean();
        private final @NonNull AtomicBoolean keepSending = new AtomicBoolean();
        private final @NonNull AtomicInteger sends = new AtomicInteger();
        private final @NonNull CountDownLatch heartbeats = new CountDownLatch(3);
        private final @NonNull CountDownLatch closed = new CountDownLatch(1);
        private boolean acknowledge = true;

        @Override
        public void send(@NonNull Frame frame) {
            recordOwner(writer);
            if (frame instanceof Frame.Hello hello) {
                deliver(new Frame.Welcome(Frame.PROTOCOL_VERSION, hello.seq(), Version.UNKNOWN));
                return;
            }
            if (frame instanceof Frame.Heartbeat) {
                heartbeats.countDown();
                if (acknowledge) deliver(new Frame.HeartbeatAck(Instant.now()));
            }
            if (frame instanceof Frame.Request && keepSending.get()) {
                sends.incrementAndGet();
                var active = client.get();
                if (active != null && !active.isClosed())
                    active.send(new Frame.Request(active.nextSequence(), null, null, "test"));
            }
        }

        private void deliver(Frame.@NonNull ServerFrame frame) {
            if (!incoming.offer(frame)) throw new AssertionError("Cannot deliver frame");
        }

        @Override
        public Frame.@NonNull ServerFrame receive() throws IOException {
            recordOwner(reader);
            try {
                var frame = incoming.take();
                if (frame == END) throw new IOException("Transport closed");
                return frame;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
            }
        }

        @Override
        public void close() {
            closed.countDown();
            deliver(END);
        }

        private void recordOwner(@NonNull AtomicReference<Thread> owner) {
            var thread = Thread.currentThread();
            var previous = owner.get();
            if (previous == null) owner.set(thread);
            else if (previous != thread) changedOwner.set(true);
        }
    }
}
