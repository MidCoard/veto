package top.focess.veto.contract;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongFunction;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.api.util.ByteBoundedQueue;
import top.focess.veto.api.util.Resources;
import top.focess.veto.api.util.Threads;

/**
 * Terminal-side protocol endpoint: owns the handshake, heartbeat and request correlation over a
 * {@link FrameTransport}. Independent virtual workers read and write that transport; backend
 * session dispatch belongs to ProtocolConnection in veto-core.
 *
 * <p>Public operations are thread-safe: one private monitor protects admission, queues, correlation
 * and deadlines, including once-only transport closure. Frame reads/writes and worker joins happen
 * outside it. Concurrent receive callers consume distinct frames. Grouped calls are not atomic:
 * send may fail after isClosed returned false.
 *
 * <p>Queued frames share their payloads by reference. Finish constructing mutable payloads before
 * enqueueing and leave them read-only afterward; see {@link Frame}.
 *
 * <p>The caller establishes the transport before constructing this client. Construction takes
 * ownership and starts both workers; Hello/Welcome negotiation is asynchronous. Close may run
 * concurrently with read and write to unblock both. The writer checks heartbeat expiry between
 * sends, so detection may wait for a bounded write.
 */
public final class ProtocolClient implements AutoCloseable {
    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.contract.ProtocolClient");
    private static final int CAPACITY = 256;
    private static final int MAX_QUEUED_BYTES = 8 * 1024 * 1024;
    private static final long REPLY_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10);

    private final @NonNull Object lock = new Object();
    // Populated only during construction; the external close owner joins the complete group.
    private final @NonNull List<@NonNull Thread> workers = new ArrayList<>(2);
    private final @NonNull ByteBoundedQueue<Frame.@NonNull ClientFrame> outgoing =
            new ByteBoundedQueue<>(CAPACITY, MAX_QUEUED_BYTES);
    private final @NonNull ByteBoundedQueue<Frame.@NonNull ServerFrame> incoming =
            new ByteBoundedQueue<>(CAPACITY, MAX_QUEUED_BYTES);
    // Null means awaiting a reply. Each query removes its entry before returning.
    private final @NonNull HashMap<@NonNull Long, Frame.@Nullable SeqResponse> pending =
            new HashMap<>();
    private final long heartbeatNanos;
    private @NonNull ProtocolClientState state = ProtocolClientState.AWAITING_WELCOME;
    private final @NonNull FrameTransport transport;
    // Welcome deadline when AWAITING_WELCOME, next heartbeat when OPEN, then acknowledgement
    // deadline.
    private long deadlineNanos;
    private long sequence = 2; // Hello owns sequence 1.

    public ProtocolClient(@NonNull FrameTransport transport) {
        this(transport, Version.UNKNOWN, System.getProperty("user.dir"), 30_000);
    }

    public ProtocolClient(
            @NonNull FrameTransport transport, @NonNull Version version, @NonNull String cwd) {
        this(transport, version, cwd, 30_000);
    }

    /**
     * Takes ownership of an established transport. Requests may queue while awaiting Welcome.
     * Receive delivers server frames, then null after the closed stream drains. Worker failures are
     * logged and close the connection; they do not create protocol frames. Invalid arguments leave
     * ownership with the caller.
     */
    public ProtocolClient(
            @NonNull FrameTransport transport,
            @NonNull Version version,
            @NonNull String cwd,
            long heartbeatMillis) {
        if (heartbeatMillis <= 0)
            throw new IllegalArgumentException("Heartbeat interval must be positive");
        this.transport = transport;
        heartbeatNanos = TimeUnit.MILLISECONDS.toNanos(heartbeatMillis);
        deadlineNanos = System.nanoTime() + REPLY_TIMEOUT_NANOS;
        workers.add(Thread.ofVirtual().name("protocol-reader").start(this::runReadLoop));
        workers.add(
                Thread.ofVirtual().name("protocol-writer").start(() -> runWriteLoop(version, cwd)));
    }

    private void runReadLoop() {
        try {
            // Shutdown owns the transport and closes it to unblock receive.
            while (true) {
                var frame = transport.receive();
                if (!(frame instanceof Frame.ServerFrame response))
                    throw new IOException("Received a client frame from the backend");
                if (!accept(response)) {
                    shutdown(null);
                    return;
                }
            }
        } catch (Throwable failure) {
            shutdown(failure);
        }
    }

    private void runWriteLoop(@NonNull Version version, @NonNull String cwd) {
        try {
            transport.send(new Frame.Hello(Frame.PROTOCOL_VERSION, 1, version, cwd));
            while (true) {
                var frame = nextOutgoing();
                if (frame == null) return;
                transport.send(frame);
            }
        } catch (Throwable failure) {
            shutdown(failure);
        }
    }

    /**
     * Queues requests during negotiation; rejects closed/full connections and payloads exceeding
     * one MiB. Admission is not delivery.
     */
    public void send(Frame.@NonNull ClientFrame frame) {
        synchronized (lock) {
            if (state == ProtocolClientState.CLOSED)
                throw new IllegalStateException("Protocol connection is closed");
            int bytes = FrameCodec.encode(frame).length;
            if (bytes > FrameTransport.MAX_FRAME_BYTES)
                throw new IllegalArgumentException("Protocol frame exceeds one MiB");
            if (!outgoing.offer(frame, bytes))
                throw new IllegalStateException("Protocol send queue is full");
            lock.notifyAll();
        }
    }

    /** Queues cancellation with a connection-owned sequence; CancelResult arrives on receive(). */
    public void cancel(long targetSeq) {
        send(new Frame.Cancel(nextSequence(), targetSeq));
    }

    public long nextSequence() {
        synchronized (lock) {
            if (state == ProtocolClientState.CLOSED)
                throw new IllegalStateException("Protocol connection is closed");
            if (sequence <= 0) throw new IllegalStateException("Protocol sequence exhausted");
            return sequence++;
        }
    }

    /** Returns null on timeout or after the closed stream drains. */
    public Frame.ServerFrame receive(long timeout, @NonNull TimeUnit unit)
            throws InterruptedException {
        long remaining = unit.toNanos(timeout);
        long deadline = System.nanoTime() + remaining;
        synchronized (lock) {
            while (incoming.isEmpty() && state != ProtocolClientState.CLOSED && remaining > 0) {
                TimeUnit.NANOSECONDS.timedWait(lock, remaining);
                remaining = deadline - System.nanoTime();
            }
            return incoming.poll();
        }
    }

    /** Waits for a frame; returns null only after the closed stream drains. */
    public Frame.ServerFrame receive() throws InterruptedException {
        synchronized (lock) {
            while (incoming.isEmpty() && state != ProtocolClientState.CLOSED) lock.wait();
            return incoming.poll();
        }
    }

    public Frame.CompleteResult complete(
            @NonNull String line, long timeout, @NonNull TimeUnit unit) {
        var response = exchange(seq -> new Frame.Complete(line, seq), timeout, unit);
        return response instanceof Frame.CompleteResult result ? result : null;
    }

    public Frame.HintResult hint(@NonNull String line, long timeout, @NonNull TimeUnit unit) {
        var response = exchange(seq -> new Frame.Hint(line, seq), timeout, unit);
        return response instanceof Frame.HintResult result ? result : null;
    }

    private <R extends Frame.SeqRequest & Frame.ClientFrame> Frame.SeqResponse exchange(
            @NonNull LongFunction<@NonNull R> request, long timeout, @NonNull TimeUnit unit) {
        synchronized (lock) {
            if (state == ProtocolClientState.AWAITING_WELCOME
                    || state == ProtocolClientState.CLOSED) return null;
            long seq = nextSequence();
            var frame = request.apply(seq);
            int bytes = FrameCodec.encode(frame).length;
            if (bytes > FrameTransport.MAX_FRAME_BYTES || !outgoing.offer(frame, bytes))
                return null;
            pending.put(seq, null);
            lock.notifyAll();
            long remaining = unit.toNanos(timeout);
            long deadline = System.nanoTime() + remaining;
            try {
                while (state != ProtocolClientState.CLOSED
                        && pending.get(seq) == null
                        && remaining > 0) {
                    TimeUnit.NANOSECONDS.timedWait(lock, remaining);
                    remaining = deadline - System.nanoTime();
                }
                return pending.get(seq);
            } catch (InterruptedException interrupted) {
                // JLine's prompt-swap wake must remain visible after this optional query returns.
                Thread.currentThread().interrupt();
                return null;
            } finally {
                pending.remove(seq);
            }
        }
    }

    private Frame.ClientFrame nextOutgoing() throws InterruptedException, TimeoutException {
        synchronized (lock) {
            while (state != ProtocolClientState.CLOSED) {
                long remaining = deadlineNanos - System.nanoTime();
                if (state == ProtocolClientState.AWAITING_WELCOME) {
                    if (remaining <= 0) throw new TimeoutException("Protocol handshake timed out");
                    TimeUnit.NANOSECONDS.timedWait(lock, remaining);
                    continue;
                }
                if (remaining <= 0) {
                    if (state == ProtocolClientState.AWAITING_HEARTBEAT_ACK)
                        throw new TimeoutException("Heartbeat acknowledgement timed out");
                    state = ProtocolClientState.AWAITING_HEARTBEAT_ACK;
                    deadlineNanos = System.nanoTime() + REPLY_TIMEOUT_NANOS;
                    return new Frame.Heartbeat();
                }
                var next = outgoing.poll();
                if (next != null) return next;
                TimeUnit.NANOSECONDS.timedWait(lock, remaining);
            }
            return null;
        }
    }

    /** Admits the actual server frame; returns whether the reader should continue. */
    private boolean accept(Frame.@NonNull ServerFrame frame) throws IOException, TimeoutException {
        synchronized (lock) {
            if (state == ProtocolClientState.CLOSED) return false;
            if (frame instanceof Frame.Terminate
                    || (state == ProtocolClientState.AWAITING_WELCOME
                            && frame instanceof Frame.Error)) {
                enqueueIncoming(frame);
                return false;
            }
            if (state == ProtocolClientState.AWAITING_WELCOME) {
                if (!(frame instanceof Frame.Welcome welcome))
                    throw new IOException("Expected protocol Welcome before application frames");
                if (welcome.seq() != 1) return true;
                if (welcome.version() != Frame.PROTOCOL_VERSION)
                    throw new IOException("Unsupported protocol version");
                if (deadlineNanos - System.nanoTime() <= 0)
                    throw new TimeoutException("Protocol handshake timed out");
                state = ProtocolClientState.OPEN;
                deadlineNanos = System.nanoTime() + heartbeatNanos;
                log.debug("Protocol connected to backend {}", welcome.productVersion());
                enqueueIncoming(welcome);
                return true;
            }
            if (frame instanceof Frame.HeartbeatAck) {
                if (state != ProtocolClientState.AWAITING_HEARTBEAT_ACK) return true;
                if (deadlineNanos - System.nanoTime() <= 0)
                    throw new TimeoutException("Heartbeat acknowledgement timed out");
                state = ProtocolClientState.OPEN;
                deadlineNanos = System.nanoTime() + heartbeatNanos;
                lock.notifyAll();
                return true;
            }
            if (frame instanceof Frame.SeqResponse sequenced
                    && pending.containsKey(sequenced.seq())
                    && pending.get(sequenced.seq()) == null) {
                pending.put(sequenced.seq(), sequenced);
                lock.notifyAll();
                return true;
            }
            enqueueIncoming(frame);
            return true;
        }
    }

    /** Caller holds the protocol monitor for queue admission and notification. */
    private void enqueueIncoming(Frame.@NonNull ServerFrame frame) {
        int bytes = FrameCodec.encode(frame).length;
        if (!incoming.offer(frame, bytes))
            throw new IllegalStateException("Protocol receive queue is full");
        lock.notifyAll();
    }

    /**
     * Thread-safe and idempotent. Releases waiting exchanges, discards pending output and closes
     * the transport to unblock both workers, then joins them. Called by the owner, outside the IO
     * workers. Repeated calls still await termination but do not close the transport again. Caller
     * interruption during joining is ignored so cleanup waits for both workers to terminate.
     * Shutdown shares the protocol monitor; all worker joins happen after releasing it. Final Bye
     * delivery is not guaranteed.
     */
    @Override
    public void close() {
        try {
            shutdown(null);
        } finally {
            while (true) {
                try {
                    Threads.joinAll(workers);
                    break;
                } catch (InterruptedException ignored) {
                    // Final close must finish joining both workers.
                }
            }
        }
    }

    private void shutdown(Throwable cause) {
        synchronized (lock) {
            if (state == ProtocolClientState.CLOSED) return;
            state = ProtocolClientState.CLOSED;
            outgoing.clear();
            lock.notifyAll();
            // Transport close releases IO resources; only the owner joins protocol workers.
            if (cause == null) {
                transport.close();
                return;
            }
            Resources.closeOnFailure(transport, cause);
        }
        log.warn("Protocol connection failed", cause);
    }

    public boolean isClosed() {
        synchronized (lock) {
            return state == ProtocolClientState.CLOSED;
        }
    }

    /** Client lifecycle state, read and updated under ProtocolClient's private lock. */
    private enum ProtocolClientState {
        AWAITING_WELCOME,
        OPEN,
        AWAITING_HEARTBEAT_ACK,
        CLOSED
    }
}
