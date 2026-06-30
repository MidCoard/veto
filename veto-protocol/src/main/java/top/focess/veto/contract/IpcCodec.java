package top.focess.veto.contract;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pure JSON codec for {@link IpcFrame} — serializes frames to bytes and deserializes them back,
 * with no dependency on any socket or transport implementation.
 *
 * <h3>Failure semantics</h3>
 *
 * <ul>
 *   <li>{@link #encode(IpcFrame)} — serialization of an in-process frame should never fail; a
 *       failure is a programming error and surfaces as {@link IpcCodecException} rather than a
 *       silent null. Callers that must not crash (e.g. an IO loop) catch it and skip the frame.
 *   <li>{@link #decode(byte[])} / {@link #decode(String)} — input comes from the network and may be
 *       malformed. Malformed payloads return {@code null} so transport layers can log-and-skip
 *       without distinguishing failure from "no message". An <em>unrecognized</em> {@code type}
 *       discriminator does not fail: Jackson's {@code defaultImpl} materializes an {@link
 *       IpcFrame.Unknown} so newer-protocol peers degrade gracefully instead of being rejected.
 * </ul>
 *
 * <h3>Thread safety</h3>
 *
 * The shared {@link ObjectMapper} is thread-safe after configuration, so all methods are safe to
 * call concurrently.
 */
public final class IpcCodec {

    private static final Logger log = LoggerFactory.getLogger(IpcCodec.class);

    static final ObjectMapper JSON = new ObjectMapper();

    static {
        // Forward-compat: a newer peer may add fields the receiver does not know. Ignore unknown
        // properties instead of rejecting the whole frame, so unknown-type frames degrade to an
        // IpcFrame.Unknown (via the @JsonTypeInfo defaultImpl) rather than failing to deserialize.
        JSON.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    private IpcCodec() {}

    // ── encode ───────────────────────────────────────────────────────────

    /**
     * Serializes an {@link IpcFrame} to a UTF-8 JSON byte array.
     *
     * @param frame the frame to serialize; must not be null
     * @return the serialized JSON bytes
     * @throws IpcCodecException if serialization fails (indicates a programming error, not bad
     *     input)
     */
    public static byte[] encode(@NonNull IpcFrame frame) {
        try {
            return JSON.writeValueAsBytes(frame);
        } catch (JsonProcessingException e) {
            throw new IpcCodecException("Failed to serialize frame: " + frame, e);
        }
    }

    /**
     * Serializes an {@link IpcFrame} to a JSON string. Convenience wrapper around {@link
     * #encode(IpcFrame)}.
     *
     * @param frame the frame to serialize; must not be null
     * @return the serialized JSON string
     * @throws IpcCodecException if serialization fails
     */
    public static @NonNull String encodeString(@NonNull IpcFrame frame) {
        return new String(encode(frame), StandardCharsets.UTF_8);
    }

    // ── decode ───────────────────────────────────────────────────────────

    /**
     * Deserializes a JSON byte array into an {@link IpcFrame}.
     *
     * @param payload the JSON bytes; must not be null
     * @return the deserialized frame, or {@code null} if the payload is malformed
     */
    public static @Nullable IpcFrame decode(byte @NonNull [] payload) {
        try {
            return JSON.readValue(payload, IpcFrame.class);
        } catch (Exception e) {
            log.warn("Failed to deserialize payload ({} bytes)", payload.length, e);
            return null;
        }
    }

    /**
     * Deserializes a JSON string into an {@link IpcFrame}.
     *
     * @param payload the JSON string; must not be null
     * @return the deserialized frame, or {@code null} if the payload is malformed
     */
    public static @Nullable IpcFrame decode(@NonNull String payload) {
        return decode(payload.getBytes(StandardCharsets.UTF_8));
    }
}
