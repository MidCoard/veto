package top.focess.veto.contract;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
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

    /** JavaTimeModule otherwise also accepts numeric epochs, outside the shared wire schema. */
    private static final class StringInstantDeserializer extends JsonDeserializer<Instant> {
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

    /** Encodes UTF-8 JSON; serialization failures surface as UncheckedIOException. */
    public static byte @NonNull [] encode(@NonNull Frame frame) {
        try {
            return JSON.writeValueAsBytes(frame);
        } catch (JsonProcessingException failure) {
            throw new UncheckedIOException("Failed to serialize protocol frame", failure);
        }
    }

    /** Encodes the same wire representation directly as text. */
    public static @NonNull String encodeString(@NonNull Frame frame) {
        try {
            return JSON.writeValueAsString(frame);
        } catch (JsonProcessingException failure) {
            throw new UncheckedIOException("Failed to serialize protocol frame", failure);
        }
    }

    /** Decodes UTF-8 JSON; malformed input returns null without logging payload contents. */
    public static Frame decode(byte @NonNull [] payload) {
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

    /** Decodes text directly using the same validation rules as the byte transport. */
    public static Frame decode(@NonNull String payload) {
        try {
            return JSON.readValue(payload, FRAME_TYPE);
        } catch (IOException failure) {
            log.warn(
                    "Rejected protocol payload ({} characters, {})",
                    payload.length(),
                    failure.getClass().getSimpleName());
            return null;
        }
    }
}
