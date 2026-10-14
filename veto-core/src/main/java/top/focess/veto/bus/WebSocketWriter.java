package top.focess.veto.bus;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.FrameCodec;

/**
 * One connection's bounded output and virtual writer. Thread-safe admission and closure use this
 * instance's monitor; socket IO and callbacks run outside it. Only the writer sends and closes the
 * socket. The container may independently close a disconnected socket.
 */
final class WebSocketWriter {
    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.bus.WebSocketWriter");
    private static final int MAX_MESSAGES = 256;
    private static final int MAX_BYTES = 1024 * 1024;

    final @NonNull WebSocketSession socket;
    private final @NonNull BooleanSupplier authenticated;
    private final @NonNull Consumer<@NonNull WebSocketWriter> onClosed;
    private final @NonNull ArrayDeque<@NonNull Pending> outbox = new ArrayDeque<>();
    private int queuedBytes;
    private CloseStatus closeStatus;
    private final @NonNull Thread writer;

    // The unstarted thread only stores this callback; start() publishes the completed instance.
    @SuppressWarnings("methodref.receiver.bound")
    WebSocketWriter(
            @NonNull WebSocketSession socket,
            @NonNull BooleanSupplier authenticated,
            @NonNull Consumer<@NonNull WebSocketWriter> onClosed) {
        this.socket = socket;
        this.authenticated = authenticated;
        this.onClosed = onClosed;
        writer = Thread.ofVirtual().name("ws-output-" + socket.getId()).unstarted(this::write);
    }

    /** Called once after registration; no event producer waits for this worker. */
    void start() {
        writer.start();
    }

    private record Pending(@NonNull TextMessage message, @NonNull BooleanSupplier attached) {}

    public boolean send(Frame.@NonNull ServerFrame frame, @NonNull BooleanSupplier attached) {
        return enqueue(new TextMessage(FrameCodec.encode(frame)), attached);
    }

    /** Nonblocking admission: at most 1 MiB queued, plus one in-flight frame of at most 1 MiB. */
    void send(@NonNull TextMessage message) {
        enqueue(message, () -> true);
    }

    private synchronized boolean enqueue(
            @NonNull TextMessage message, @NonNull BooleanSupplier attached) {
        if (closeStatus != null) return false;
        if (outbox.size() >= MAX_MESSAGES || message.getPayloadLength() > MAX_BYTES - queuedBytes) {
            close(CloseStatus.SESSION_NOT_RELIABLE.withReason("Outbound buffer limit exceeded"));
            return false;
        }
        outbox.addLast(new Pending(message, attached));
        queuedBytes += message.getPayloadLength();
        notifyAll();
        return true;
    }

    /** Stop accepting output and send admitted protocol replies before closing. */
    synchronized void finish(@NonNull CloseStatus status) {
        if (closeStatus == null) closeStatus = status;
        notifyAll();
    }

    /** Discard pending output; the writer closes after its current bounded socket write returns. */
    synchronized void close(@NonNull CloseStatus status) {
        if (closeStatus == null) closeStatus = status;
        outbox.clear();
        queuedBytes = 0;
        notifyAll();
    }

    synchronized boolean isOpen() {
        return closeStatus == null;
    }

    boolean awaitTermination(@NonNull Duration timeout) throws InterruptedException {
        return writer.join(timeout);
    }

    private synchronized Pending next() throws InterruptedException {
        while (outbox.isEmpty() && closeStatus == null) wait();
        var message = outbox.pollFirst();
        if (message != null) queuedBytes -= message.message().getPayloadLength();
        return message;
    }

    private void write() {
        try {
            Pending message;
            while ((message = next()) != null) {
                if (!socket.isOpen()) break;
                if (!authenticated.getAsBoolean()) {
                    close(CloseStatus.POLICY_VIOLATION.withReason("authentication expired"));
                    break;
                }
                if (message.attached().getAsBoolean()) socket.sendMessage(message.message());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Throwable failure) {
            log.warn(
                    "WS Bus: Writer failed for '{}' ({})",
                    socket.getId(),
                    failure.getClass().getSimpleName());
            close(CloseStatus.SERVER_ERROR);
        } finally {
            CloseStatus status;
            synchronized (this) {
                var reason = closeStatus;
                status = reason == null ? CloseStatus.NORMAL : reason;
                close(status);
            }
            try {
                if (socket.isOpen()) socket.close(status);
            } catch (Throwable failure) {
                log.debug(
                        "WS Bus: Could not close '{}' ({})",
                        socket.getId(),
                        failure.getClass().getSimpleName());
            } finally {
                onClosed.accept(this);
            }
        }
    }
}
