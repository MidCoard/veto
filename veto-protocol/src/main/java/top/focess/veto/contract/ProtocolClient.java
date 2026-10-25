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
import top.focess.veto.api.util.Threads;

/**
 * Terminal-side protocol endpoint: owns the handshake, heartbeat and request correlation over a
 * {@link FrameTransport}. Independent virtual workers read and write that transport; backend
 * session dispatch belongs to ProtocolConnection in veto-core.
 *
 * <p>Public operations are thread-safe: one private monitor protects lifecycle, correlation and
 * deadlines, including once-only transport closure. The byte queues own capacity waiting and
 * draining. Frame reads/writes, capacity waits and worker joins happen outside the protocol
 * monitor. Concurrent receive callers consume distinct frames. Grouped calls are not atomic: send
 * may fail after isClosed returned false.
 *
 * <p>Queued frames share their payloads by reference. Finish constructing mutable payloads before
 * enqueueing and leave them read-only afterward; see {@link Frame}.
 *
 * <p>The caller establishes the transport before constructing this client. Construction takes
 * ownership and starts both workers; Hello/Welcome negotiation is asynchronous. Close may run
 * concurrently with read and write to unblock both. The writer sends a heartbeat only when no
 * business frame is queued and outbound IO has been idle. The transport owns receive-idle
 * detection; any incoming frame counts as activity, without a separate heartbeat acknowledgement
 * deadline.
 */
public final class ProtocolClient implements AutoCloseable {
    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.contract.ProtocolClient");
    private static final int MAX_QUEUED_FRAMES = 256;
    // Per-queue backlog budget, independent of the maximum size of a single JSON Frame.
    private static final int MAX_QUEUED_BYTES = 64 * 1024 * 1024;
    private static final long HANDSHAKE_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10);

    private final @NonNull Object lock = new Object();
    // Populated only during construction; the external close owner joins the complete group.
    private final @NonNull List<@NonNull Thread> workers = new ArrayList<>(2);
    private final @NonNull ByteBoundedQueue<Frame.@NonNull ClientFrame> outgoing =
            new ByteBoundedQueue<>(MAX_QUEUED_FRAMES, MAX_QUEUED_BYTES);
    private final @NonNull ByteBoundedQueue<Frame.@NonNull ServerFrame> incoming =
            new ByteBoundedQueue<>(MAX_QUEUED_FRAMES, MAX_QUEUED_BYTES);
    // Null means awaiting a reply. Each query removes its entry before returning.
    private final @NonNull HashMap<@NonNull Long, Frame.@Nullable SeqResponse> pending =
            new HashMap<>();
    private final long heartbeatNanos;
    private @NonNull ProtocolClientState state = ProtocolClientState.INITIALIZED;
    private final @NonNull FrameTransport transport;
    // Handshake start until Welcome; then the baseline for outbound idle time.
    private long lastSendNanos;
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
        lastSendNanos = System.nanoTime();
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
                synchronized (lock) {
                    lastSendNanos = System.nanoTime();
                }
            }
        } catch (Throwable failure) {
            shutdown(failure);
        }
    }

    /**
     * Queues requests during negotiation. A full queue waits up to ten seconds for capacity;
     * closure and interruption end the wait. Admission is not delivery.
     *
     * @throws InterruptedException when cancellation interrupts the capacity wait
     */
    public void send(Frame.@NonNull ClientFrame frame) throws InterruptedException {
        synchronized (lock) {
            if (state == ProtocolClientState.CLOSED)
                throw new IllegalStateException("Protocol connection is closed");
        }
        int bytes = FrameCodec.encode(frame).length;
        if (bytes > FrameCodec.MAX_ENCODED_FRAME_BYTES)
            throw new IllegalArgumentException("Protocol message exceeds 64 MiB");
        if (!outgoing.offer(frame, bytes, 10, TimeUnit.SECONDS))
            throw new IllegalStateException(
                    "Protocol send queue closed or capacity wait timed out");
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    /** Queues cancellation with a connection-owned sequence; CancelResult arrives on receive(). */
    public void cancel(long targetSeq) throws InterruptedException {
        send(new Frame.Cancel(nextSequence(), targetSeq));
    }

    public long nextSequence() {
        synchronized (lock) {
            if (state == ProtocolClientState.CLOSED)
                throw new IllegalStateException("Protocol connection is closed");
            return sequence++;
        }
    }

    /** Returns null on timeout or after the closed stream drains. */
    public Frame.ServerFrame receive(long timeout, @NonNull TimeUnit unit)
            throws InterruptedException {
        return incoming.poll(timeout, unit);
    }

    /** Waits for a frame; returns null only after the closed stream drains. */
    public Frame.ServerFrame receive() throws InterruptedException {
        return incoming.take();
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
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        long seq;
        R frame;
        synchronized (lock) {
            if (state != ProtocolClientState.RUNNING) return null;
            seq = sequence++;
            frame = request.apply(seq);
            pending.put(seq, null);
        }
        try {
            int bytes = FrameCodec.encode(frame).length;
            if (bytes > FrameCodec.MAX_ENCODED_FRAME_BYTES
                    || !outgoing.offer(
                            frame, bytes, deadline - System.nanoTime(), TimeUnit.NANOSECONDS))
                return null;
            synchronized (lock) {
                lock.notifyAll();
                long remaining = deadline - System.nanoTime();
                while (state != ProtocolClientState.CLOSED
                        && pending.get(seq) == null
                        && remaining > 0) {
                    TimeUnit.NANOSECONDS.timedWait(lock, remaining);
                    remaining = deadline - System.nanoTime();
                }
                return pending.get(seq);
            }
        } catch (InterruptedException interrupted) {
            // JLine's prompt-swap wake must remain visible after this optional query returns.
            Thread.currentThread().interrupt();
            return null;
        } finally {
            synchronized (lock) {
                pending.remove(seq);
            }
        }
    }

    private Frame.ClientFrame nextOutgoing() throws InterruptedException, TimeoutException {
        synchronized (lock) {
            while (state != ProtocolClientState.CLOSED) {
                if (state == ProtocolClientState.INITIALIZED) {
                    long remaining = HANDSHAKE_TIMEOUT_NANOS - (System.nanoTime() - lastSendNanos);
                    if (remaining <= 0) throw new TimeoutException("Protocol handshake timed out");
                    TimeUnit.NANOSECONDS.timedWait(lock, remaining);
                    continue;
                }
                var next = outgoing.poll();
                if (next != null) return next;
                long remaining = heartbeatNanos - (System.nanoTime() - lastSendNanos);
                if (remaining <= 0) return new Frame.Heartbeat();
                TimeUnit.NANOSECONDS.timedWait(lock, remaining);
            }
            return null;
        }
    }

    /** Admits the actual server frame; returns whether the reader should continue. */
    private boolean accept(Frame.@NonNull ServerFrame frame)
            throws IOException, TimeoutException, InterruptedException {
        boolean terminal;
        synchronized (lock) {
            if (state == ProtocolClientState.CLOSED) return false;
            terminal =
                    frame instanceof Frame.Terminate
                            || (state == ProtocolClientState.INITIALIZED
                                    && frame instanceof Frame.Error);
        }
        if (terminal) {
            enqueueIncoming(frame);
            return false;
        }
        synchronized (lock) {
            if (state == ProtocolClientState.CLOSED) return false;
            if (state == ProtocolClientState.INITIALIZED) {
                if (!(frame instanceof Frame.Welcome welcome))
                    throw new IOException("Expected protocol Welcome before application frames");
                if (welcome.seq() != 1) return true;
                if (welcome.version() != Frame.PROTOCOL_VERSION)
                    throw new IOException("Unsupported protocol version");
                if (System.nanoTime() - lastSendNanos >= HANDSHAKE_TIMEOUT_NANOS)
                    throw new TimeoutException("Protocol handshake timed out");
                state = ProtocolClientState.RUNNING;
                lastSendNanos = System.nanoTime();
                log.debug("Protocol connected to backend {}", welcome.productVersion());
                lock.notifyAll();
            } else if (frame instanceof Frame.HeartbeatAck) {
                return true;
            } else if (frame instanceof Frame.SeqResponse sequenced
                    && pending.containsKey(sequenced.seq())
                    && pending.get(sequenced.seq()) == null) {
                pending.put(sequenced.seq(), sequenced);
                lock.notifyAll();
                return true;
            }
        }
        enqueueIncoming(frame);
        return true;
    }

    /** Shared admission boundary for terminal and ordinary input; never holds the protocol lock. */
    private void enqueueIncoming(Frame.@NonNull ServerFrame frame)
            throws IOException, InterruptedException {
        if (!incoming.offer(frame, FrameCodec.encode(frame).length, 10, TimeUnit.SECONDS))
            throw new IOException("Protocol receive queue closed or capacity wait timed out");
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
        shutdown(null);
        while (true) {
            try {
                Threads.joinAll(workers);
                break;
            } catch (InterruptedException ignored) {
                // Final close must finish joining both workers.
            }
        }
    }

    private void shutdown(Throwable cause) {
        synchronized (lock) {
            if (state == ProtocolClientState.CLOSED) return;
            state = ProtocolClientState.CLOSED;
            outgoing.close(false);
            incoming.close(true);
            lock.notifyAll();
            // Transport close releases IO resources; only the owner joins protocol workers.
            transport.close();
        }
        if (cause != null) log.warn("Protocol connection failed", cause);
    }

    public boolean isClosed() {
        synchronized (lock) {
            return state == ProtocolClientState.CLOSED;
        }
    }

    /** Client lifecycle state, read and updated under ProtocolClient's private lock. */
    private enum ProtocolClientState {
        INITIALIZED,
        RUNNING,
        CLOSED
    }
}
