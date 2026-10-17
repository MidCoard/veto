package top.focess.veto.contract;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongFunction;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.api.util.Resources;
import top.focess.veto.api.util.Threads;

/**
 * Terminal-side protocol endpoint: owns the handshake, heartbeat and request correlation over a
 * {@link FrameTransport}. Independent virtual workers read and write that transport; backend
 * session dispatch belongs to ProtocolConnection in veto-core.
 *
 * <p>Public operations are thread-safe: one private monitor protects admission, queues, correlation
 * and deadlines; socket IO and future completion happen outside it. Concurrent receive callers
 * consume distinct frames. Grouped calls are not atomic: send may fail after isClosed returned
 * false.
 *
 * <p>Queued frames share their payloads by reference. Finish constructing mutable payloads before
 * enqueueing and leave them read-only afterward; see {@link Frame}.
 *
 * <p>The factory runs on the reader and must clean up partial setup failures. The returned
 * transport belongs to this client; close may run concurrently with read and write to unblock both.
 * The writer checks heartbeat expiry between sends, so detection may wait for a bounded write.
 */
public final class ProtocolClient implements AutoCloseable {
    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.contract.ProtocolClient");
    private static final int CAPACITY = 256;
    private static final int MAX_QUEUED_BYTES = 8 * 1024 * 1024;
    private static final long REPLY_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10);

    private final @NonNull Object lock = new Object();
    private final @NonNull CompletableFuture<@NonNull Version> ready = new CompletableFuture<>();
    private final @NonNull ArrayDeque<@NonNull QueuedFrame<Frame.@NonNull ClientFrame>> outgoing =
            new ArrayDeque<>();
    private final @NonNull ArrayDeque<@NonNull QueuedFrame<Frame.@NonNull ServerFrame>> incoming =
            new ArrayDeque<>();
    private final @NonNull
            HashMap<@NonNull Long, @NonNull CompletableFuture<Frame.@NonNull SeqResponse>>
            pending = new HashMap<>();
    private final long heartbeatNanos;
    private final @NonNull Thread reader;
    private @NonNull ProtocolClientState state = ProtocolClientState.CONNECTING;
    private FrameTransport transport;
    private Thread writer;
    // Next heartbeat when OPEN; acknowledgement deadline when AWAITING_HEARTBEAT_ACK.
    private long heartbeatAt;
    private long sequence = 2; // Hello owns sequence 1.
    private int outgoingBytes;
    private int incomingBytes;

    public ProtocolClient(@NonNull Callable<@NonNull FrameTransport> factory)
            throws InterruptedException, ExecutionException, TimeoutException {
        this(factory, Version.UNKNOWN, System.getProperty("user.dir"), 30_000);
    }

    public ProtocolClient(
            @NonNull Callable<@NonNull FrameTransport> factory,
            @NonNull Version version,
            @NonNull String cwd)
            throws InterruptedException, ExecutionException, TimeoutException {
        this(factory, version, cwd, 30_000);
    }

    /**
     * Waits up to ten seconds for transport setup and Welcome. Startup failures retain their
     * original cause in ExecutionException; timeout/interruption closes the connection, interrupts
     * pending setup and propagates unchanged.
     */
    // Workers only use fields initialized before start; neither reads the reader handle.
    @SuppressWarnings("method.invocation")
    public ProtocolClient(
            @NonNull Callable<@NonNull FrameTransport> factory,
            @NonNull Version version,
            @NonNull String cwd,
            long heartbeatMillis)
            throws InterruptedException, ExecutionException, TimeoutException {
        if (heartbeatMillis <= 0)
            throw new IllegalArgumentException("Heartbeat interval must be positive");
        heartbeatNanos = TimeUnit.MILLISECONDS.toNanos(heartbeatMillis);
        reader =
                Thread.ofVirtual().name("protocol-reader").start(() -> read(factory, version, cwd));
        try {
            log.debug(
                    "Protocol connected to backend {}",
                    ready.get(REPLY_TIMEOUT_NANOS, TimeUnit.NANOSECONDS));
        } catch (InterruptedException | TimeoutException failure) {
            shutdown(failure);
            reader.interrupt();
            close();
            throw failure;
        }
    }

    private void read(
            @NonNull Callable<@NonNull FrameTransport> factory,
            @NonNull Version version,
            @NonNull String cwd) {
        try {
            var connection = factory.call();
            boolean installed;
            synchronized (lock) {
                installed = state != ProtocolClientState.CLOSED;
                if (installed) {
                    transport = connection;
                    var hello = new Frame.Hello(Frame.PROTOCOL_VERSION, 1, version, cwd);
                    writer =
                            Thread.ofVirtual()
                                    .name("protocol-writer")
                                    .start(() -> write(connection, hello));
                }
            }
            if (!installed) {
                connection.close();
                return;
            }
            // Shutdown owns the installed transport and closes it to unblock receive.
            while (true) {
                var frame = connection.receive();
                if (!(frame instanceof Frame.ServerFrame response))
                    throw new IOException("Received a client frame from the backend");
                accept(response);
            }
        } catch (Throwable failure) {
            shutdown(failure);
        }
    }

    private void write(@NonNull FrameTransport connection, Frame.@NonNull Hello hello) {
        try {
            connection.send(hello);
            while (true) {
                var frame = nextOutgoing();
                if (frame == null) return;
                connection.send(frame);
            }
        } catch (Throwable failure) {
            shutdown(failure);
        }
    }

    /**
     * Rejects closed/full connections and payloads exceeding one MiB; admission is not delivery.
     */
    public void send(Frame.@NonNull ClientFrame frame) {
        synchronized (lock) {
            if (state == ProtocolClientState.CONNECTING || state == ProtocolClientState.CLOSED)
                throw new IllegalStateException("Protocol connection is not open");
            enqueue(frame);
        }
    }

    /** Queues cancellation with a connection-owned sequence; CancelResult arrives on receive(). */
    public void cancel(long targetSeq) {
        send(new Frame.Cancel(nextSequence(), targetSeq));
    }

    public long nextSequence() {
        synchronized (lock) {
            if (state == ProtocolClientState.CONNECTING || state == ProtocolClientState.CLOSED)
                throw new IllegalStateException("Protocol connection is not open");
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
            return pollIncoming();
        }
    }

    /** Waits for a frame; returns null only after the closed stream drains. */
    public Frame.ServerFrame receive() throws InterruptedException {
        synchronized (lock) {
            while (incoming.isEmpty() && state != ProtocolClientState.CLOSED) lock.wait();
            return pollIncoming();
        }
    }

    /** Caller holds the monitor; removing a frame releases its queue byte budget. */
    private Frame.ServerFrame pollIncoming() {
        var next = incoming.pollFirst();
        if (next == null) return null;
        incomingBytes -= next.bytes();
        return next.frame();
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
        var response = new CompletableFuture<Frame.@NonNull SeqResponse>();
        long seq;
        synchronized (lock) {
            if (state == ProtocolClientState.CONNECTING || state == ProtocolClientState.CLOSED)
                return null;
            seq = nextSequence();
            try {
                enqueue(request.apply(seq));
            } catch (IllegalStateException | IllegalArgumentException rejected) {
                return null;
            }
            pending.put(seq, response);
        }
        try {
            return response.get(timeout, unit);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException unavailable) {
            return null;
        } finally {
            synchronized (lock) {
                pending.remove(seq, response);
            }
        }
        return null;
    }

    private void enqueue(Frame.@NonNull ClientFrame frame) {
        int bytes = FrameCodec.encode(frame).length;
        if (bytes > FrameTransport.MAX_FRAME_BYTES)
            throw new IllegalArgumentException("Protocol frame exceeds one MiB");
        if (outgoing.size() == CAPACITY || bytes > MAX_QUEUED_BYTES - outgoingBytes)
            throw new IllegalStateException("Protocol send queue is full");
        outgoing.addLast(new QueuedFrame<>(frame, bytes));
        outgoingBytes += bytes;
        lock.notifyAll();
    }

    private Frame.ClientFrame nextOutgoing() throws InterruptedException, TimeoutException {
        synchronized (lock) {
            while (state != ProtocolClientState.CLOSED) {
                if (state == ProtocolClientState.CONNECTING) {
                    lock.wait();
                    continue;
                }
                long remaining = heartbeatAt - System.nanoTime();
                if (remaining <= 0) {
                    if (state == ProtocolClientState.AWAITING_HEARTBEAT_ACK)
                        throw new TimeoutException("Heartbeat acknowledgement timed out");
                    state = ProtocolClientState.AWAITING_HEARTBEAT_ACK;
                    heartbeatAt = System.nanoTime() + REPLY_TIMEOUT_NANOS;
                    return new Frame.Heartbeat();
                }
                var next = outgoing.pollFirst();
                if (next != null) {
                    outgoingBytes -= next.bytes();
                    return next.frame();
                }
                TimeUnit.NANOSECONDS.timedWait(lock, remaining);
            }
            return null;
        }
    }

    private void accept(Frame.@NonNull ServerFrame frame) throws TimeoutException {
        CompletableFuture<Frame.@NonNull SeqResponse> response;
        Version welcomed = null;
        synchronized (lock) {
            if (state == ProtocolClientState.CLOSED) return;
            if (state == ProtocolClientState.CONNECTING) {
                if (frame instanceof Frame.Welcome welcome) {
                    if (welcome.version() != Frame.PROTOCOL_VERSION)
                        throw new IllegalStateException("Unsupported protocol version");
                    if (welcome.seq() != 1) return;
                    state = ProtocolClientState.OPEN;
                    heartbeatAt = System.nanoTime() + heartbeatNanos;
                    welcomed = welcome.productVersion();
                    lock.notifyAll();
                } else if (frame instanceof Frame.Error || frame instanceof Frame.Terminate)
                    throw new IllegalStateException("Backend rejected protocol handshake");
            }
            if (frame instanceof Frame.HeartbeatAck) {
                if (state == ProtocolClientState.AWAITING_HEARTBEAT_ACK) {
                    if (heartbeatAt - System.nanoTime() <= 0)
                        throw new TimeoutException("Heartbeat acknowledgement timed out");
                    state = ProtocolClientState.OPEN;
                    heartbeatAt = System.nanoTime() + heartbeatNanos;
                    lock.notifyAll();
                }
                return;
            }
            response =
                    frame instanceof Frame.SeqResponse sequenced
                            ? pending.remove(sequenced.seq())
                            : null;
            if (welcomed == null && response == null) {
                int bytes = FrameCodec.encode(frame).length;
                if (incoming.size() == CAPACITY || bytes > MAX_QUEUED_BYTES - incomingBytes)
                    throw new IllegalStateException("Protocol receive queue is full");
                incoming.addLast(new QueuedFrame<>(frame, bytes));
                incomingBytes += bytes;
                lock.notifyAll();
                return;
            }
        }
        if (welcomed != null) ready.complete(welcomed);
        if (response != null) response.complete((Frame.SeqResponse) frame);
    }

    /**
     * Thread-safe and idempotent. Fails waiting exchanges, discards pending output and closes the
     * transport to unblock both workers. No final Bye delivery is promised.
     */
    @Override
    public void close() {
        shutdown(null);
        Thread output;
        synchronized (lock) {
            output = writer;
        }
        Threads.awaitTermination(reader, 3_000);
        Threads.awaitTermination(output, 3_000);
    }

    private void shutdown(Throwable cause) {
        ArrayList<@NonNull CompletableFuture<Frame.@NonNull SeqResponse>> detached;
        FrameTransport connection;
        synchronized (lock) {
            if (state == ProtocolClientState.CLOSED) return;
            state = ProtocolClientState.CLOSED;
            connection = transport;
            transport = null;
            outgoing.clear();
            outgoingBytes = 0;
            detached = new ArrayList<>(pending.values());
            pending.clear();
            lock.notifyAll();
        }
        var failure =
                cause == null ? new IllegalStateException("Protocol connection closed") : cause;
        detached.forEach(response -> response.completeExceptionally(failure));
        if (connection != null) Resources.closeOnFailure(connection, failure);
        if (!ready.completeExceptionally(failure)
                && (cause != null || failure.getSuppressed().length != 0))
            log.warn("Protocol connection failed", failure);
    }

    public boolean isClosed() {
        synchronized (lock) {
            return state == ProtocolClientState.CLOSED;
        }
    }

    public int negotiatedVersion() {
        return Frame.PROTOCOL_VERSION;
    }

    public @NonNull Version serverProductVersion() {
        return ready.join();
    }

    /** Client lifecycle state, read and updated under ProtocolClient's private lock. */
    private enum ProtocolClientState {
        CONNECTING,
        OPEN,
        AWAITING_HEARTBEAT_ACK,
        CLOSED
    }

    /**
     * A queued frame and its byte cost captured at admission, preserving queue-limit accounting.
     */
    private record QueuedFrame<F extends Frame>(@NonNull F frame, int bytes) {}
}
