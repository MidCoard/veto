package top.focess.veto.contract;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Correlates sequenced requests with their responses — the single source of sequence numbers and
 * the single registry of pending seq handlers for an {@code IpcClient}.
 *
 * <p>Extracted from the prior {@code ZmqClient}, where seq allocation, delivery routing, and
 * await/cleanup were scattered across {@code send}, {@code route}, and {@code receive}.
 * Centralizing them makes the correlation logic unit-testable without a socket and eliminates the
 * seq-1 overlap between the handshake and the first request: the handshake now draws its seq from
 * {@link #next()} like every other request, so the first user request gets {@code 2}, not a reused
 * {@code 1}.
 *
 * <h2>Lifecycle of a sequenced exchange</h2>
 *
 * <ol>
 *   <li>{@link #next()} allocates a monotonic seq.
 *   <li>{@link #register(long)} creates a single-slot queue for the expected response (called
 *       before the request is sent, so a fast response is never missed).
 *   <li>The IO loop calls {@link #deliver(IpcFrame.SeqResponse)} for each incoming sequenced
 *       response, routing it to the matching queue.
 *   <li>The requester calls {@link #await(long, long, TimeUnit)} to block for the response; the
 *       handler is removed in {@code finally} so it never leaks.
 * </ol>
 *
 * <p>A response with {@code seq == 0} is never correlated (e.g. a streaming {@link IpcFrame.Error}
 * emitted in reply to a {@link IpcFrame.Request}, which has no seq); {@link #deliver} ignores it.
 *
 * <h2>Thread safety</h2>
 *
 * All state is in thread-safe collections / atomics; all methods are safe to call concurrently.
 */
public final class SeqCorrelator {

    private static final Logger log = LoggerFactory.getLogger(SeqCorrelator.class);

    private final AtomicLong nextSeq = new AtomicLong(1);
    private final ConcurrentHashMap<Long, BlockingQueue<IpcFrame.SeqResponse>> handlers =
            new ConcurrentHashMap<>();

    /** Allocates the next monotonic sequence number, starting at {@code 1}. */
    public long next() {
        return nextSeq.getAndIncrement();
    }

    /**
     * Registers a single-slot response queue for the given seq. Must be called before the request
     * is sent so a fast response is not dropped.
     *
     * @param seq the sequence number to register
     */
    public void register(long seq) {
        handlers.put(seq, new LinkedBlockingQueue<>(1));
    }

    /**
     * Routes a sequenced response to its registered queue. A response with {@code seq == 0}, or one
     * with no registered handler, is logged and dropped — never throws.
     *
     * @param response the incoming sequenced response
     */
    public void deliver(@NotNull IpcFrame.SeqResponse response) {
        long seq = response.seq();
        if (seq == 0) return;
        BlockingQueue<IpcFrame.SeqResponse> queue = handlers.get(seq);
        if (queue != null) {
            queue.offer(response);
        } else {
            log.debug("No handler registered for seq={} — dropping {}", seq, response);
        }
    }

    /**
     * Blocks up to {@code timeout} for the response to {@code seq}, then removes the handler.
     *
     * @param seq the sequence number to await
     * @param timeout the maximum time to wait
     * @param unit the time unit
     * @return the response, or {@code null} on timeout or if no handler was registered
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    @Nullable
    public IpcFrame.SeqResponse await(long seq, long timeout, @NotNull TimeUnit unit)
            throws InterruptedException {
        BlockingQueue<IpcFrame.SeqResponse> queue = handlers.get(seq);
        if (queue == null) return null;
        try {
            return queue.poll(timeout, unit);
        } finally {
            handlers.remove(seq);
        }
    }

    /** Removes the handler for {@code seq} without waiting, freeing the slot. */
    public void discard(long seq) {
        handlers.remove(seq);
    }
}
