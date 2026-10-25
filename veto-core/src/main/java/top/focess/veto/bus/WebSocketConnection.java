package top.focess.veto.bus;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.FrameCodec;

/**
 * Native WebSocket transport. Ordered receive callbacks own partial input; the protocol endpoint
 * serializes sends. Native socket close can run concurrently to unblock a failed or stopped send.
 */
final class WebSocketConnection {
    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.bus.WebSocketConnection");
    final @NonNull WebSocketSession socket;
    private StringBuilder receivedText;
    private int receivedBytes;

    WebSocketConnection(@NonNull WebSocketSession socket) {
        this.socket = socket;
    }

    Frame receive(@NonNull TextMessage message) throws IOException {
        var buffer = receivedText;
        if (buffer == null) {
            buffer = new StringBuilder();
            receivedText = buffer;
        }
        var part = message.getPayload();
        int bytes = message.getPayloadLength();
        if (!buffer.isEmpty()
                && !part.isEmpty()
                && Character.isHighSurrogate(buffer.charAt(buffer.length() - 1))
                && Character.isLowSurrogate(part.charAt(0))) bytes += 2;
        if (bytes > FrameCodec.MAX_ENCODED_FRAME_BYTES - receivedBytes)
            throw new IOException("WebSocket Frame exceeds the JSON byte limit");
        buffer.append(part);
        receivedBytes += bytes;
        if (!message.isLast()) return null;
        receivedText = null;
        receivedBytes = 0;
        return FrameCodec.decode(buffer.toString());
    }

    void send(Frame.@NonNull ServerFrame frame) throws IOException {
        if (!socket.isOpen()) throw new ClosedChannelException();
        socket.sendMessage(new TextMessage(FrameCodec.encodeString(frame)));
    }

    void close(@NonNull CloseStatus status) {
        try {
            if (socket.isOpen()) socket.close(status);
        } catch (IOException failure) {
            log.debug("WS: Could not close '{}'", socket.getId(), failure);
        }
    }
}
