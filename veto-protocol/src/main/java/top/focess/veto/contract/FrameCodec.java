package top.focess.veto.contract;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pure JSON codec for {@link Frame} — serializes frames to bytes and deserializes them back, with
 * no dependency on any socket or transport implementation.
 *
 * <h3>Failure semantics</h3>
 *
 * <ul>
 *   <li>{@link #encode(Frame)} — serialization of an in-process frame should never fail; a failure
 *       is a programming error and surfaces as {@link FrameCodecException} rather than a silent
 *       null. Callers that must not crash (e.g. an IO loop) catch it and skip the frame.
 *   <li>{@link #decode(byte[])} / {@link #decode(String)} — input comes from the network and may be
 *       malformed. Malformed payloads return {@code null} so transport layers can log-and-skip
 *       without distinguishing failure from "no message". Unknown type discriminators are rejected.
 * </ul>
 *
 * <h3>Thread safety</h3>
 *
 * The shared {@link ProtocolJson} codec is thread-safe after configuration, so all methods are safe
 * to call concurrently.
 */
public final class FrameCodec {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.contract.FrameCodec");

    private FrameCodec() {}

    // ── encode ───────────────────────────────────────────────────────────

    /**
     * Serializes an {@link Frame} to a UTF-8 JSON byte array.
     *
     * @param frame the frame to serialize; must not be null
     * @return the serialized JSON bytes
     * @throws FrameCodecException if serialization fails (indicates a programming error, not bad
     *     input)
     */
    public static byte @NonNull [] encode(@NonNull Frame frame) {
        try {
            return ProtocolJson.encode(frame);
        } catch (JsonProcessingException e) {
            throw new FrameCodecException("Failed to serialize frame: " + frame, e);
        }
    }

    /**
     * Serializes an {@link Frame} to a JSON string. Convenience wrapper around {@link
     * #encode(Frame)}.
     *
     * @param frame the frame to serialize; must not be null
     * @return the serialized JSON string
     * @throws FrameCodecException if serialization fails
     */
    public static @NonNull String encodeString(@NonNull Frame frame) {
        return new String(encode(frame), StandardCharsets.UTF_8);
    }

    // ── decode ───────────────────────────────────────────────────────────

    /**
     * Deserializes a JSON byte array into an {@link Frame}.
     *
     * @param payload the JSON bytes; must not be null
     * @return the deserialized frame, or {@code null} if the payload is malformed
     */
    public static Frame decode(byte @NonNull [] payload) {
        try {
            return ProtocolJson.decode(payload, new TypeReference<Frame>() {});
        } catch (Exception e) {
            log.warn("Failed to deserialize payload ({} bytes)", payload.length, e);
            return null;
        }
    }

    /**
     * Deserializes a JSON string into an {@link Frame}.
     *
     * @param payload the JSON string; must not be null
     * @return the deserialized frame, or {@code null} if the payload is malformed
     */
    public static Frame decode(@NonNull String payload) {
        return decode(payload.getBytes(StandardCharsets.UTF_8));
    }
}
