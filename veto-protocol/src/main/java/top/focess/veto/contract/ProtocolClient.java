package top.focess.veto.contract;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
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
 *
 * <p>The supplied factory opens a transport on the IO worker and may throw an unchecked setup
 * failure. It must clean up any resources allocated before failing. A successfully returned
 * transport belongs to this client and is closed by the same worker.
 */
public final class ProtocolClient implements AutoCloseable {
    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.contract.ProtocolClient");
    private static final int CAPACITY = 256;
    private static final int POLL_MILLIS = 50;
    private final @NonNull ConnectionState state = new ConnectionState();
    private final @NonNull Thread ioThread;
    private final @NonNull Version serverProductVersion;

    /**
     * Connects using the default version, working directory and heartbeat interval.
     *
     * @throws RuntimeException if transport creation, handshake or startup waiting fails
     * @throws Error if transport creation or handshake raises an error
     */
    public ProtocolClient(@NonNull Supplier<@NonNull ClientTransport> factory) {
        this(factory, Version.UNKNOWN, System.getProperty("user.dir"), 30_000);
    }

    /**
     * Connects using the default heartbeat interval.
     *
     * @throws RuntimeException if transport creation, handshake or startup waiting fails
     * @throws Error if transport creation or handshake raises an error
     */
    public ProtocolClient(
            @NonNull Supplier<@NonNull ClientTransport> factory,
            @NonNull Version version,
            @NonNull String cwd) {
        this(factory, version, cwd, 30_000);
    }

    /**
     * Creates the transport and waits for its handshake before returning.
     *
     * @param factory opens a transport on the IO worker; setup failures propagate to this caller
     * @throws RuntimeException if transport creation, handshake or startup waiting fails
     * @throws IllegalArgumentException if the heartbeat interval is not positive
     * @throws Error if transport creation or handshake raises an error
     */
    public ProtocolClient(
            @NonNull Supplier<@NonNull ClientTransport> factory,
            @NonNull Version version,
            @NonNull String cwd,
            long heartbeatMillis) {
        if (heartbeatMillis <= 0)
            throw new IllegalArgumentException("Heartbeat interval must be positive");
        var ready = new CompletableFuture<@NonNull Version>();
        var connection = state;
        ioThread =
                Thread.ofVirtual()
                        .name("protocol-io")
                        .unstarted(
                                () ->
                                        run(
                                                factory,
                                                version,
                                                cwd,
                                                TimeUnit.MILLISECONDS.toNanos(heartbeatMillis),
                                                connection,
                                                ready));
        ioThread.start();
        try {
            serverProductVersion = ready.get();
        } catch (InterruptedException interrupted) {
            state.close();
            awaitStop(ioThread);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Protocol connection interrupted", interrupted);
        } catch (ExecutionException failure) {
            var cause = failure.getCause();
            if (cause instanceof RuntimeException exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Protocol connection failed", cause);
        }
    }

    private static void run(
            @NonNull Supplier<@NonNull ClientTransport> factory,
            @NonNull Version version,
            @NonNull String cwd,
            long heartbeatNanos,
            @NonNull ConnectionState state,
            @NonNull CompletableFuture<@NonNull Version> ready) {
        // This opens the transport and may throw before the try body is entered.
        try (var transport = factory.get()) {
            try {
                var productVersion = handshake(transport, version, cwd, state);
                state.open();
                ready.complete(productVersion);
                communicate(transport, state, heartbeatNanos);
                transport.send(new Frame.Bye());
            } finally {
                state.close();
            }
        } catch (Throwable failure) {
            // factory.get() can fail before the inner finally; that lifecycle must also end CLOSED.
            state.close();
            if (!ready.completeExceptionally(failure))
                log.warn("Protocol connection failed ({})", failure.getClass().getSimpleName());
        }
    }

    private static @NonNull Version handshake(
            @NonNull ClientTransport transport,
            @NonNull Version version,
            @NonNull String cwd,
            @NonNull ConnectionState state) {
        long seq = 1;
        transport.send(new Frame.Hello(Frame.PROTOCOL_VERSION, seq, version, cwd));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!state.isClosed()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new IllegalStateException("Protocol handshake timed out");
            var frame =
                    transport.recv(
                            Math.clamp(TimeUnit.NANOSECONDS.toMillis(remaining), 1, POLL_MILLIS));
            if (frame == null) continue;
            if (frame instanceof Frame.Welcome welcome) {
                if (welcome.version() != Frame.PROTOCOL_VERSION)
                    throw new IllegalStateException("Unsupported protocol version");
                if (welcome.seq() == seq) return welcome.productVersion();
            } else if (frame instanceof Frame.Error || frame instanceof Frame.Terminate) {
                throw new IllegalStateException("Backend rejected protocol handshake");
            } else state.accept(frame);
        }
        throw new IllegalStateException("Protocol connection closed during handshake");
    }

    private static void communicate(
            @NonNull ClientTransport transport,
            @NonNull ConnectionState state,
            long heartbeatNanos) {
        long lastHeartbeat = System.nanoTime();
        while (!state.isClosed()) {
            // Bound each batch so sustained producers cannot starve receive or heartbeat.
            for (int i = 0; i < CAPACITY; i++) {
                var frame = state.nextOutgoing();
                if (frame == null || state.isClosed()) break;
                transport.send(frame);
            }
            if (state.isClosed()) return;
            long elapsed = System.nanoTime() - lastHeartbeat;
            if (elapsed >= heartbeatNanos) {
                transport.send(new Frame.Heartbeat());
                lastHeartbeat = System.nanoTime();
                elapsed = 0;
            }
            long wait = Math.max(1, TimeUnit.NANOSECONDS.toMillis(heartbeatNanos - elapsed));
            var frame = transport.recv(Math.min(POLL_MILLIS, wait));
            if (frame != null) state.accept(frame);
        }
    }

    /** Queues a frame, rejecting a closed/full connection instead of silently dropping work. */
    public void send(Frame.@NonNull ClientFrame frame) {
        state.send(frame);
    }

    /** Returns the next stream frame, or null on timeout or after the closed stream drains. */
    public Frame.ServerFrame receive(long timeout, @NonNull TimeUnit unit)
            throws InterruptedException {
        return state.receive(unit.toNanos(timeout));
    }

    public Frame.ServerFrame receive() throws InterruptedException {
        return receive(120, TimeUnit.SECONDS);
    }

    public Frame.CompleteResult complete(
            @NonNull String line, long timeout, @NonNull TimeUnit unit) {
        var response = state.exchange(seq -> new Frame.Complete(line, seq), timeout, unit);
        return response instanceof Frame.CompleteResult result ? result : null;
    }

    public Frame.HintResult hint(@NonNull String line, long timeout, @NonNull TimeUnit unit) {
        var response = state.exchange(seq -> new Frame.Hint(line, seq), timeout, unit);
        return response instanceof Frame.HintResult result ? result : null;
    }

    /**
     * Wakes callers immediately and lets the IO owner close after its current bounded socket call.
     */
    @Override
    public void close() {
        state.close();
        awaitStop(ioThread);
    }

    private static void awaitStop(@NonNull Thread worker) {
        if (worker != Thread.currentThread()) {
            try {
                worker.join(3_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public boolean isClosed() {
        return state.isClosed();
    }

    public int negotiatedVersion() {
        return Frame.PROTOCOL_VERSION;
    }

    public @NonNull Version serverProductVersion() {
        return serverProductVersion;
    }

    private enum Phase {
        CONNECTING,
        OPEN,
        CLOSED
    }

    /**
     * CONNECTING -> OPEN on Welcome; CONNECTING/OPEN -> CLOSED on close or failure. CLOSED is
     * terminal: callers wake immediately, while the IO owner retires the socket. This object alone
     * owns admission, queues and correlation under its monitor.
     */
    private static final class ConnectionState {
        private @NonNull Phase phase = Phase.CONNECTING;
        private long sequence = 2; // Hello owns sequence 1.
        private final @NonNull ArrayDeque<Frame.@NonNull ClientFrame> outgoing = new ArrayDeque<>();
        private final @NonNull ArrayDeque<Frame.@NonNull ServerFrame> incoming = new ArrayDeque<>();
        private final @NonNull
                HashMap<@NonNull Long, @NonNull CompletableFuture<Frame.@NonNull SeqResponse>>
                pending = new HashMap<>();

        private synchronized void open() {
            switch (phase) {
                case CONNECTING -> phase = Phase.OPEN;
                case OPEN, CLOSED -> throw new IllegalStateException("Connection cannot be opened");
            }
        }

        private synchronized boolean isClosed() {
            return phase == Phase.CLOSED;
        }

        private synchronized void send(Frame.@NonNull ClientFrame frame) {
            if (phase != Phase.OPEN)
                throw new IllegalStateException("Protocol connection is not open");
            if (outgoing.size() == CAPACITY)
                throw new IllegalStateException("Protocol send queue is full");
            outgoing.addLast(frame);
        }

        private synchronized Frame.ClientFrame nextOutgoing() {
            return outgoing.pollFirst();
        }

        private <R extends Frame.SeqRequest & Frame.ClientFrame> Frame.SeqResponse exchange(
                @NonNull LongFunction<@NonNull R> request, long timeout, @NonNull TimeUnit unit) {
            var response = new CompletableFuture<Frame.@NonNull SeqResponse>();
            long seq;
            synchronized (this) {
                if (phase != Phase.OPEN || outgoing.size() == CAPACITY) return null;
                seq = sequence++;
                var frame = request.apply(seq);
                pending.put(seq, response);
                outgoing.addLast(frame);
            }
            try {
                return response.get(timeout, unit);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | TimeoutException unavailable) {
                return null;
            } finally {
                synchronized (this) {
                    pending.remove(seq, response);
                }
            }
            return null;
        }

        private void accept(Frame.@NonNull ServerFrame frame) {
            if (frame instanceof Frame.HeartbeatAck) return;
            CompletableFuture<Frame.@NonNull SeqResponse> response;
            synchronized (this) {
                if (phase == Phase.CLOSED) return;
                response =
                        frame instanceof Frame.SeqResponse sequenced
                                ? pending.remove(sequenced.seq())
                                : null;
                if (response == null) {
                    if (incoming.size() == CAPACITY)
                        throw new IllegalStateException("Protocol receive queue is full");
                    incoming.addLast(frame);
                    notifyAll();
                    return;
                }
            }
            Frame.SeqResponse sequenced = (Frame.SeqResponse) frame;
            response.complete(sequenced);
        }

        private synchronized Frame.ServerFrame receive(long remaining) throws InterruptedException {
            long deadline = System.nanoTime() + remaining;
            while (incoming.isEmpty() && phase != Phase.CLOSED && remaining > 0) {
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
                remaining = deadline - System.nanoTime();
            }
            return incoming.pollFirst();
        }

        private void close() {
            ArrayList<@NonNull CompletableFuture<Frame.@NonNull SeqResponse>> detached;
            synchronized (this) {
                switch (phase) {
                    case CLOSED -> {
                        return;
                    }
                    case CONNECTING, OPEN -> phase = Phase.CLOSED;
                }
                outgoing.clear();
                detached = new ArrayList<>(pending.values());
                pending.clear();
                notifyAll();
            }
            // Completing a future may execute callbacks; never do that under the state monitor.
            var failure = new IllegalStateException("Protocol connection closed");
            detached.forEach(response -> response.completeExceptionally(failure));
        }
    }
}
