package top.focess.veto.bus;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.focess.veto.contract.Delta;
import top.focess.veto.contract.Frame.ScopedFrame;

/**
 * Sits between the agent loop and the transport layer ({@link DeltaBusBridge}) and multiplexes
 * per-session {@link Delta} streams to subscribed consumers.
 *
 * <p>The broker atomically assigns increasing sequence numbers per session. Subscribers run inline
 * on each publisher's thread, with session subscribers before wildcard subscribers. Concurrent
 * publications may overlap and reach a subscriber out of sequence; assigning a sequence does not
 * serialize callback delivery. The transport layer (e.g. the WebSocket bus) is one such consumer;
 * tests and other transports can subscribe in parallel.
 *
 * <p>Subscription collections support concurrent changes and use copy-on-write iteration snapshots.
 * Consumers own coordination of their shared state. Runtime exceptions from one consumer are logged
 * and skipped; errors propagate. The broker provides no buffering, replay, acknowledgement, or
 * remote-delivery guarantee.
 */
@Component
public class DeltaBroker {

    /** Per-session listeners. */
    private final @NonNull ConcurrentMap<UUID, List<Consumer<ScopedFrame>>> listeners =
            new ConcurrentHashMap<>();

    /** Wildcard subscribers (receive every frame regardless of sessionId). */
    private final @NonNull List<Consumer<ScopedFrame>> wildcardListeners =
            new CopyOnWriteArrayList<>();

    /** Per-session monotonic sequence. */
    private final @NonNull ConcurrentMap<UUID, AtomicLong> sequences = new ConcurrentHashMap<>();

    /** Subscribe to a session's frame stream. Returns a handle that unsubscribes on close. */
    public @NonNull AutoCloseable subscribe(
            @NonNull UUID sessionId, @NonNull Consumer<ScopedFrame> listener) {
        listeners.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>()).add(listener);
        return () -> {
            List<Consumer<ScopedFrame>> list = listeners.get(sessionId);
            if (list != null) {
                list.remove(listener);
            }
        };
    }

    /**
     * Subscribe to <b>every</b> session's frame stream — a wildcard subscription used by transports
     * (e.g. the WebSocket bus) that fan out to a single downstream client and don't care which
     * session each frame originated from. Returns a handle that unsubscribes on close.
     */
    public @NonNull AutoCloseable subscribeAll(@NonNull Consumer<ScopedFrame> listener) {
        wildcardListeners.add(listener);
        return () -> wildcardListeners.remove(listener);
    }

    /** Publish a frame: assigns a sequence, fans out to all subscribers of the session. */
    public void publish(@NonNull ScopedFrame frame) {
        UUID sessionId = frame.sessionId();
        if (sessionId == null)
            throw new IllegalArgumentException("Session broker requires a session id");
        ScopedFrame sequenced = frame;
        if (frame instanceof Delta delta) {
            long seq =
                    sequences.computeIfAbsent(sessionId, k -> new AtomicLong()).incrementAndGet();
            sequenced = new Delta(delta.scope(), seq, delta.emittedAt(), delta.data());
        }
        List<Consumer<ScopedFrame>> subs = listeners.getOrDefault(sessionId, List.of());
        for (Consumer<ScopedFrame> sub : subs) {
            try {
                sub.accept(sequenced);
            } catch (RuntimeException e) {
                // Don't let one bad subscriber block the others, but log it so a broken
                // transport is diagnosable (was previously swallowed silently).
                LoggerFactory.getLogger("top.focess.veto.bus.DeltaBroker")
                        .warn(
                                "DeltaBroker: subscriber threw on session {} (frame={})",
                                sessionId,
                                sequenced.getClass().getSimpleName(),
                                e);
            }
        }
        for (Consumer<ScopedFrame> sub : wildcardListeners) {
            try {
                sub.accept(sequenced);
            } catch (RuntimeException e) {
                LoggerFactory.getLogger("top.focess.veto.bus.DeltaBroker")
                        .warn(
                                "DeltaBroker: wildcard subscriber threw on session {} (frame={})",
                                sessionId,
                                sequenced.getClass().getSimpleName(),
                                e);
            }
        }
    }

    /** Test-only: list of subscriber counts per session. */
    public @NonNull Map<UUID, Integer> subscriberCounts() {
        Map<UUID, Integer> out = new HashMap<>();
        for (var entry : listeners.entrySet()) {
            out.put(entry.getKey(), entry.getValue().size());
        }
        return out;
    }
}
