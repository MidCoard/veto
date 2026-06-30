package top.focess.veto.bus;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * The Part 8 Delta-frame broker. Sits between the agent loop and the transport layer ({@link
 * WebSocketBus}); multiplexes per-session {@link DeltaFrame} streams to subscribed consumers.
 *
 * <p>Per-session sequence is monotonic (assigned by the broker on publish). Consumers subscribe to
 * a session id and receive frames in order. The transport layer (e.g. the WebSocket bus) is one
 * such consumer; tests and other transports can subscribe in parallel.
 */
@Component
public class DeltaBroker {

    private final @NonNull ObjectMapper mapper;

    /** Per-session listeners. */
    private final ConcurrentMap<UUID, List<Consumer<DeltaFrame>>> listeners =
            new ConcurrentHashMap<>();

    /** Wildcard subscribers (receive every frame regardless of sessionId). */
    private final List<Consumer<DeltaFrame>> wildcardListeners = new CopyOnWriteArrayList<>();

    /** Per-session monotonic sequence. */
    private final ConcurrentMap<UUID, AtomicLong> sequences = new ConcurrentHashMap<>();

    public
    @NonNull
    DeltaBroker(@NonNull ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Subscribe to a session's frame stream. Returns a handle that unsubscribes on close. */
    public @NonNull AutoCloseable subscribe(
            @NonNull UUID sessionId, @NonNull Consumer<DeltaFrame> listener) {
        listeners.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>()).add(listener);
        return () -> {
            List<Consumer<DeltaFrame>> list = listeners.get(sessionId);
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
    public @NonNull AutoCloseable subscribeAll(@NonNull Consumer<DeltaFrame> listener) {
        wildcardListeners.add(listener);
        return () -> wildcardListeners.remove(listener);
    }

    /** Publish a frame: assigns a sequence, fans out to all subscribers of the session. */
    public void publish(@NonNull DeltaFrame frame) {
        long seq =
                sequences
                        .computeIfAbsent(frame.sessionId(), k -> new AtomicLong(0))
                        .incrementAndGet();
        DeltaFrame sequenced =
                new DeltaFrame(
                        frame.sessionId(),
                        seq,
                        frame.emittedAt(),
                        frame.kind(),
                        frame.text(),
                        frame.attrs());
        List<Consumer<DeltaFrame>> subs = listeners.getOrDefault(frame.sessionId(), List.of());
        for (Consumer<DeltaFrame> sub : subs) {
            try {
                sub.accept(sequenced);
            } catch (RuntimeException e) {
                // Don't let one bad subscriber block the others, but log it so a broken
                // transport is diagnosable (was previously swallowed silently).
                org.slf4j.LoggerFactory.getLogger(DeltaBroker.class)
                        .warn(
                                "DeltaBroker: subscriber threw on session {} (frame seq={},"
                                        + " kind={})",
                                frame.sessionId(),
                                sequenced.sequence(),
                                sequenced.kind(),
                                e);
            }
        }
        for (Consumer<DeltaFrame> sub : wildcardListeners) {
            try {
                sub.accept(sequenced);
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(DeltaBroker.class)
                        .warn(
                                "DeltaBroker: wildcard subscriber threw on session {} (frame"
                                        + " seq={}, kind={})",
                                frame.sessionId(),
                                sequenced.sequence(),
                                sequenced.kind(),
                                e);
            }
        }
    }

    /** Convenience: publish + serialize to JSON. */
    public void publishJson(@NonNull DeltaFrame frame) {
        publish(frame);
        // Touch mapper so it stays referenced (the publish above fans out the structured frame;
        // JSON serialization happens at the transport).
        @SuppressWarnings("unused")
        ObjectMapper m = mapper;
    }

    /** Test-only: list of subscriber counts per session. */
    public Map<UUID, Integer> subscriberCounts() {
        Map<UUID, Integer> out = new java.util.HashMap<>();
        for (var entry : listeners.entrySet()) {
            out.put(entry.getKey(), entry.getValue().size());
        }
        return out;
    }
}
