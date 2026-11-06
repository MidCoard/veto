package top.focess.veto.contract;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.deser.InstantDeserializer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared JSON codec for every Frame, independent of socket framing and application mappers.
 *
 * <p><b>Thread safety:</b> all static operations support concurrent calls. The private mapper is
 * fully configured during class initialization and is never reconfigured afterward. Callers must
 * not mutate a frame's payload during encoding or an input byte array during decoding. Returned
 * byte arrays belong to the caller; decoded mutable payloads follow the sharing rules in {@link
 * Frame}. Codec thread safety does not make those payloads thread-safe.
 */
public final class FrameCodec {
    /**
     * Maximum bytes in one complete UTF-8 JSON frame, excluding WebSocket headers. This is neither
     * a socket packet size nor a queue backlog budget. Decoding enforces it here; senders check the
     * encoded size before IO or queue admission.
     */
    public static final int MAX_ENCODED_FRAME_BYTES = 64 * 1024 * 1024;

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.contract.FrameCodec");
    private static final @NonNull TypeReference<@NonNull Frame> FRAME_TYPE =
            new TypeReference<>() {};

    private static final @NonNull ObjectMapper JSON =
            new ObjectMapper()
                    .registerModule(
                            new JavaTimeModule()
                                    .addDeserializer(
                                            Instant.class, new StringInstantDeserializer()))
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                    .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    static {
        JSON.getFactory()
                .setStreamReadConstraints(
                        StreamReadConstraints.builder()
                                .maxStringLength(MAX_ENCODED_FRAME_BYTES)
                                .build());
        JSON.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        JSON.coercionConfigFor(LogicalType.Integer)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        JSON.coercionConfigFor(LogicalType.Boolean)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
    }

    private FrameCodec() {}

    /**
     * Serializes a complete frame, including its JSON type discriminator. Encoding does not enforce
     * the transport byte limit or split the result; the sender checks the resulting size.
     *
     * @param frame value with a stable payload for the duration of serialization
     * @return newly allocated UTF-8 JSON bytes, without socket headers
     * @throws UncheckedIOException if the value cannot be serialized
     */
    public static byte @NonNull [] encode(@NonNull Frame frame) {
        try {
            return JSON.writeValueAsBytes(frame);
        } catch (JsonProcessingException failure) {
            throw new UncheckedIOException("Failed to serialize protocol frame", failure);
        }
    }

    /**
     * Serializes the same JSON representation directly as text, for text-based transports. Like
     * {@link #encode(Frame)}, it leaves the encoded-size check to the sender.
     *
     * @param frame value with a stable payload for the duration of serialization
     * @return JSON text; its UTF-8 encoded size, not String.length(), determines the wire byte cost
     * @throws UncheckedIOException if the value cannot be serialized
     */
    public static @NonNull String encodeString(@NonNull Frame frame) {
        try {
            return JSON.writeValueAsString(frame);
        } catch (JsonProcessingException failure) {
            throw new UncheckedIOException("Failed to serialize protocol frame", failure);
        }
    }

    /**
     * Decodes one complete frame and validates the shared wire schema. Rejection logs size and
     * exception type, never payload contents. Callers decide how to terminate or reject bad input.
     *
     * @param payload complete UTF-8 JSON without a transport header; do not mutate during decoding
     * @return decoded frame, or null for JSON null, malformed/invalid data or an exceeded byte
     *     limit
     */
    public static Frame decode(byte @NonNull [] payload) {
        if (payload.length > MAX_ENCODED_FRAME_BYTES) {
            log.warn("Rejected protocol payload ({} bytes, size limit exceeded)", payload.length);
            return null;
        }
        try {
            return JSON.readValue(payload, FRAME_TYPE);
        } catch (IOException failure) {
            log.warn(
                    "Rejected protocol payload ({} bytes, {})",
                    payload.length,
                    failure.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * Decodes text under the same UTF-8 byte budget and schema as {@link #decode(byte[])}. The
     * initial character-count check only avoids an unnecessary allocation for obviously large
     * inputs; UTF-8 bytes determine the actual limit.
     *
     * @param payload complete JSON text from a text transport
     * @return decoded frame, or null for JSON null, malformed/invalid data or an exceeded byte
     *     limit
     */
    public static Frame decode(@NonNull String payload) {
        if (payload.length() > MAX_ENCODED_FRAME_BYTES) {
            log.warn(
                    "Rejected protocol text ({} characters, size limit exceeded)",
                    payload.length());
            return null;
        }
        return decode(payload.getBytes(StandardCharsets.UTF_8));
    }

    /** JavaTimeModule otherwise also accepts numeric epochs, outside the shared wire schema. */
    private static final class StringInstantDeserializer extends JsonDeserializer<Instant> {
        /**
         * Restricts timestamps to the JSON string representation shared with browser clients.
         *
         * @param parser parser positioned at the timestamp value
         * @param context Jackson context used by its ISO instant parser
         * @return parsed, non-null timestamp
         * @throws IOException if the value is not a valid, nonempty timestamp string
         */
        @Override
        public @NonNull Instant deserialize(
                @NonNull JsonParser parser, @NonNull DeserializationContext context)
                throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_STRING))
                throw MismatchedInputException.from(
                        parser, Instant.class, "Expected a timestamp string");
            var instant = InstantDeserializer.INSTANT.deserialize(parser, context);
            if (instant == null)
                throw MismatchedInputException.from(
                        parser, Instant.class, "Expected a nonempty timestamp string");
            return instant;
        }
    }
}
