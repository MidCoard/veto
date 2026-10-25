package top.focess.veto.contract;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ProtocolClientWorkerTest {
    @Test
    @Timeout(5)
    void idleHeartbeatsNeedNoMatchingAcknowledgementAndUseIndependentWorkers() throws Exception {
        exerciseWorkers(false);
    }

    @Test
    @Timeout(5)
    void businessOutputPostponesHeartbeatsWhileReceiveAndCloseRemainIndependent() throws Exception {
        exerciseWorkers(true);
    }

    private void exerciseWorkers(boolean busy) throws Exception {
        var transport = new RecordingTransport();
        var client = new ProtocolClient(transport, Version.UNKNOWN, "", 100);
        transport.client.set(client);
        transport.keepSending.set(busy);
        try (client) {
            assertInstanceOf(Frame.Welcome.class, client.receive(1, TimeUnit.SECONDS));
            if (busy) {
                client.send(new Frame.Request(client.nextSequence(), null, null, "test"));
                var progress = Delta.message("progress");
                transport.deliver(progress);
                assertEquals(progress, client.receive(1, TimeUnit.SECONDS));
                assertFalse(transport.firstHeartbeat.await(300, TimeUnit.MILLISECONDS));
                assertTrue(transport.sends.get() > 1, "Business output must keep progressing");
                transport.keepSending.set(false);
            }
            assertTrue(
                    transport.heartbeats.await(2, TimeUnit.SECONDS), "Idle keepalives must resume");
            assertFalse(client.isClosed(), "HeartbeatAck is not required before another keepalive");
            var reader = transport.reader.get();
            var writer = transport.writer.get();
            if (reader == null || writer == null) throw new AssertionError("Missing IO worker");
            assertTrue(reader.isVirtual());
            assertTrue(writer.isVirtual());
            assertNotSame(reader, writer);
            assertFalse(transport.changedOwner.get(), "Each IO direction needs one owner");
        } finally {
            transport.keepSending.set(false);
        }
        assertTrue(transport.closed.await(1, TimeUnit.SECONDS));
        var reader = transport.reader.get();
        var writer = transport.writer.get();
        if (reader == null || writer == null) throw new AssertionError("Missing IO worker");
        assertFalse(reader.isAlive());
        assertFalse(writer.isAlive());
    }

    @Test
    @Timeout(5)
    void concurrentCloseUsesPrivateMonitorAndJoinsBothWorkersDespiteInterruption()
            throws Exception {
        var transport = new RecordingTransport();
        transport.blockRequests = true;
        transport.holdExit = true;
        var client = new ProtocolClient(transport);
        var callerInterrupted = new AtomicBoolean();
        var closingCaller = new AtomicReference<Thread>();
        try (var closingWorkers = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                synchronized (client) {
                    assertInstanceOf(Frame.Welcome.class, client.receive(1, TimeUnit.SECONDS));
                    client.send(new Frame.Request(client.nextSequence(), null, null, "blocked"));
                    assertTrue(transport.blockedWrite.await(1, TimeUnit.SECONDS));
                    var first =
                            closingWorkers.submit(
                                    () -> {
                                        Thread.currentThread().interrupt();
                                        closingCaller.set(Thread.currentThread());
                                        client.close();
                                        callerInterrupted.set(
                                                Thread.currentThread().isInterrupted());
                                        return null;
                                    });
                    assertTrue(transport.exiting.await(1, TimeUnit.SECONDS));
                    var second =
                            closingWorkers.submit(
                                    () -> {
                                        client.close();
                                        return null;
                                    });
                    assertFalse(first.isDone(), "close must wait after the transport is released");
                    assertFalse(second.isDone(), "another close must also wait for worker exit");
                    var status = closingWorkers.submit(client::isClosed);
                    assertTrue(
                            Boolean.TRUE.equals(status.get(1, TimeUnit.SECONDS)),
                            "Worker joins must not hold the protocol monitor");
                    var caller = closingCaller.get();
                    if (caller == null) throw new AssertionError("Missing close caller");
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                    while (caller.isInterrupted() && deadline - System.nanoTime() > 0)
                        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                    assertFalse(caller.isInterrupted(), "close must consume the caught interrupt");
                    transport.releaseExit.countDown();
                    assertNull(first.get(1, TimeUnit.SECONDS));
                    assertNull(second.get(1, TimeUnit.SECONDS));
                    assertFalse(
                            callerInterrupted.get(), "Final close must not restore interruption");
                    assertEquals(1, transport.closes.get());
                    var reader = transport.reader.get();
                    var writer = transport.writer.get();
                    if (reader == null || writer == null)
                        throw new AssertionError("Missing IO workers");
                    assertFalse(reader.isAlive());
                    assertFalse(writer.isAlive());
                }
            } finally {
                transport.releaseExit.countDown();
                client.close();
            }
        }
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
        private final @NonNull CountDownLatch firstHeartbeat = new CountDownLatch(1);
        private final @NonNull CountDownLatch blockedWrite = new CountDownLatch(1);
        private final @NonNull CountDownLatch closed = new CountDownLatch(1);
        private final @NonNull AtomicInteger closes = new AtomicInteger();
        private final @NonNull CountDownLatch exiting = new CountDownLatch(2);
        private final @NonNull CountDownLatch releaseExit = new CountDownLatch(1);
        private boolean holdExit;
        private boolean blockRequests;

        @Override
        public void send(@NonNull Frame frame) throws IOException {
            recordOwner(writer);
            if (frame instanceof Frame.Hello hello) {
                deliver(new Frame.Welcome(Frame.PROTOCOL_VERSION, hello.seq(), Version.UNKNOWN));
                return;
            }
            if (frame instanceof Frame.Heartbeat) {
                heartbeats.countDown();
                firstHeartbeat.countDown();
            }
            try {
                if (frame instanceof Frame.Request && blockRequests) {
                    blockedWrite.countDown();
                    if (!closed.await(15, TimeUnit.SECONDS))
                        throw new IOException("Simulated write timed out");
                    if (holdExit) {
                        exiting.countDown();
                        releaseExit.await();
                    }
                    throw new IOException("Transport closed");
                }
                if (frame instanceof Frame.Request && keepSending.get()) {
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                    sends.incrementAndGet();
                    var active = client.get();
                    if (active != null && !active.isClosed())
                        active.send(new Frame.Request(active.nextSequence(), null, null, "test"));
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
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
                if (frame == END) {
                    if (holdExit) {
                        exiting.countDown();
                        releaseExit.await();
                    }
                    throw new IOException("Transport closed");
                }
                return frame;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
            }
        }

        @Override
        public void close() {
            closes.incrementAndGet();
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
