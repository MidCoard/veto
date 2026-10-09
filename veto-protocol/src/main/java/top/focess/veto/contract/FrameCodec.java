package top.focess.veto.contract;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.core.type.TypeReference;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared JSON codec for every Frame, independent of socket framing and application mappers. */
public final class FrameCodec {
    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.contract.FrameCodec");
    private static final @NonNull TypeReference<@NonNull Frame> FRAME_TYPE =
            new TypeReference<>() {};

    private static final @NonNull ObjectMapper JSON =
            new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
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
