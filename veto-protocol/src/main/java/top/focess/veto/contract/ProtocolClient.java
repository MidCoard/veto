package top.focess.veto.contract;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongFunction;
import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A polling protocol connection. One virtual worker creates, handshakes, uses and closes its
 * transport. Callers exchange typed frames through bounded queues, never through the socket.
 */
public final class ProtocolClient implements AutoCloseable {
    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.contract.ProtocolClient");
    private static final int CAPACITY = 256;
    private static final int POLL_MILLIS = 50;
    private final @NonNull Object state = new Object();
    private final @NonNull ArrayDeque<Frame.@NonNull ClientFrame> outgoing = new ArrayDeque<>();
    private final @NonNull ArrayDeque<Frame.@NonNull ServerFrame> incoming = new ArrayDeque<>();
    private final @NonNull HashMap<@NonNull Long, @NonNull CompletableFuture<Frame.@NonNull SeqResponse>> pending = new HashMap<>();
    private final @NonNull CompletableFuture<@NonNull Version> ready = new CompletableFuture<>();
    private final @NonNull Thread ioThread;
    private @NonNull Version serverProductVersion = Version.UNKNOWN;
    private boolean closed;
    private long sequence = 1;

    public ProtocolClient(@NonNull Supplier<@NonNull ClientTransport> factory) {
        this(factory, Version.UNKNOWN, System.getProperty("user.dir"), 30_000);
    }

    public ProtocolClient(@NonNull Supplier<@NonNull ClientTransport> factory,
            @NonNull Version version, @NonNull String cwd) {
        this(factory, version, cwd, 30_000);
    }

    public ProtocolClient(@NonNull Supplier<@NonNull ClientTransport> factory,
            @NonNull Version version, @NonNull String cwd, long heartbeatMillis) {
        if (heartbeatMillis <= 0)
            throw new IllegalArgumentException("Heartbeat interval must be positive");
        ioThread = Thread.ofVirtual().name("protocol-io")
                .unstarted(() -> run(factory, version, cwd, TimeUnit.MILLISECONDS.toNanos(heartbeatMillis)));
        ioThread.start();
        try {
            serverProductVersion = ready.get();
        } catch (InterruptedException interrupted) {
            close();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Protocol connection interrupted", interrupted);
        } catch (ExecutionException failure) {
            var cause = failure.getCause();
            if (cause instanceof RuntimeException exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Protocol connection failed", cause);
        }
    }

    private void run(@NonNull Supplier<@NonNull ClientTransport> factory,
            @NonNull Version version, @NonNull String cwd, long heartbeatNanos) {
        try (var transport = factory.get()) {
            ready.complete(handshake(transport, version, cwd));
            long lastHeartbeat = System.nanoTime();
            while (!isClosed()) {
                // One bounded batch leaves receive, heartbeat and close responsive to producers.
                for (int i = 0; i < CAPACITY; i++) {
                    Frame.ClientFrame frame;
                    synchronized (state) {
                        frame = outgoing.pollFirst();
                    }
                    if (frame == null || isClosed()) break;
                    transport.send(frame);
                }
                if (isClosed()) break;
                long elapsed = System.nanoTime() - lastHeartbeat;
                if (elapsed >= heartbeatNanos) {
                    transport.send(new Frame.Heartbeat(0));
                    lastHeartbeat = System.nanoTime();
                    elapsed = 0;
                }
                long wait = Math.max(1, TimeUnit.NANOSECONDS.toMillis(heartbeatNanos - elapsed));
                var frame = transport.recv(Math.min(POLL_MILLIS, wait));
                if (frame != null) route(frame);
            }
            // Unsent application frames are discarded on close; only this final control frame remains.
            transport.send(new Frame.Bye());
        } catch (RuntimeException | Error failure) {
            if (!ready.completeExceptionally(failure) && !isClosed())
                log.warn("Protocol connection failed ({})", failure.getClass().getSimpleName());
        } finally {
            closeState();
        }
    }

    private @NonNull Version handshake(@NonNull ClientTransport transport,
            @NonNull Version version, @NonNull String cwd) {
        long seq;
        synchronized (state) {
            seq = sequence++;
        }
        transport.send(new Frame.Hello(Frame.PROTOCOL_VERSION, seq, version, cwd));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!isClosed()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new IllegalStateException("Protocol handshake timed out");
            var frame = transport.recv(Math.max(1, Math.min(POLL_MILLIS, TimeUnit.NANOSECONDS.toMillis(remaining))));
            if (frame == null) continue;
            if (frame instanceof Frame.Welcome welcome) {
                if (welcome.version() != Frame.PROTOCOL_VERSION)
                    throw new IllegalStateException("Unsupported protocol version");
                if (welcome.seq() == seq) return welcome.productVersion();
            } else if (frame instanceof Frame.Error || frame instanceof Frame.Terminate) {
                throw new IllegalStateException("Backend rejected protocol handshake");
            } else route(frame);
        }
        throw new IllegalStateException("Protocol connection closed during handshake");
    }

    private void route(Frame.@NonNull ServerFrame frame) {
        if (frame instanceof Frame.HeartbeatAck) return;
        CompletableFuture<Frame.@NonNull SeqResponse> response = null;
        synchronized (state) {
            if (closed) return;
            if (frame instanceof Frame.SeqResponse sequenced) response = pending.remove(sequenced.seq());
            if (response == null) {
                if (incoming.size() == CAPACITY)
                    throw new IllegalStateException("Protocol receive queue is full");
                incoming.addLast(frame);
                state.notifyAll();
                return;
            }
        }
        if (frame instanceof Frame.SeqResponse sequenced) response.complete(sequenced);
    }

    /** Queues a frame, rejecting a closed/full connection instead of silently dropping work. */
    public void send(Frame.@NonNull ClientFrame frame) {
        synchronized (state) {
            enqueue(frame);
        }
    }

    private void enqueue(Frame.@NonNull ClientFrame frame) {
        if (closed) throw new IllegalStateException("Protocol connection is closed");
        if (outgoing.size() == CAPACITY) throw new IllegalStateException("Protocol send queue is full");
        outgoing.addLast(frame);
    }

    /** Returns the next stream frame, or null on timeout or after the closed stream drains. */
    public Frame.ServerFrame receive(long timeout, @NonNull TimeUnit unit) throws InterruptedException {
        long remaining = unit.toNanos(timeout);
        long deadline = System.nanoTime() + remaining;
        synchronized (state) {
            while (incoming.isEmpty() && !closed && remaining > 0) {
                TimeUnit.NANOSECONDS.timedWait(state, remaining);
                remaining = deadline - System.nanoTime();
            }
            return incoming.pollFirst();
        }
    }

    public Frame.ServerFrame receive() throws InterruptedException {
        return receive(120, TimeUnit.SECONDS);
    }

    private Frame.SeqResponse exchange(@NonNull LongFunction<Frame.@NonNull SeqRequest> request,
            long timeout, @NonNull TimeUnit unit) {
        long seq;
        var response = new CompletableFuture<Frame.@NonNull SeqResponse>();
        synchronized (state) {
            if (closed || outgoing.size() == CAPACITY) return null;
            seq = sequence++;
            pending.put(seq, response);
            enqueue(request.apply(seq));
        }
        try {
            return response.get(timeout, unit);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException unavailable) {
            return null;
        } finally {
            synchronized (state) {
                pending.remove(seq, response);
            }
        }
        return null;
    }

    public Frame.CompleteResult complete(@NonNull String line, long timeout, @NonNull TimeUnit unit) {
        var response = exchange(seq -> new Frame.Complete(line, seq), timeout, unit);
        return response instanceof Frame.CompleteResult result ? result : null;
    }

    public Frame.HintResult hint(@NonNull String line, long timeout, @NonNull TimeUnit unit) {
        var response = exchange(seq -> new Frame.Hint(line, seq), timeout, unit);
        return response instanceof Frame.HintResult result ? result : null;
    }

    private void closeState() {
        List<CompletableFuture<Frame.@NonNull SeqResponse>> detached;
        synchronized (state) {
            if (closed) return;
            closed = true;
            outgoing.clear();
            detached = new ArrayList<>(pending.values());
            pending.clear();
            state.notifyAll();
        }
        var failure = new IllegalStateException("Protocol connection closed");
        detached.forEach(future -> future.completeExceptionally(failure));
    }

    /** Wakes callers immediately and lets the IO owner close after its current bounded socket call. */
    @Override
    public void close() {
        closeState();
        if (ioThread != Thread.currentThread()) {
            try {
                ioThread.join(3_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public boolean isClosed() {
        synchronized (state) {
            return closed;
        }
    }

    public int negotiatedVersion() {
        return Frame.PROTOCOL_VERSION;
    }

    public @NonNull Version serverProductVersion() {
        return serverProductVersion;
    }
}
