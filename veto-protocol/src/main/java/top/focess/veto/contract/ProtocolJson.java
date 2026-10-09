package top.focess.veto.contract;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.NonNull;

/**
 * Shared JSON representation for protocol values, independent of sockets and application mappers.
 */
public final class ProtocolJson {

    private static final @NonNull ObjectMapper JSON =
            new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private ProtocolJson() {}

    /** Encodes a protocol value as UTF-8 JSON bytes. */
    public static byte @NonNull [] encode(@NonNull Object value) throws JsonProcessingException {
        return JSON.writeValueAsBytes(value);
    }

    /** Encodes the same representation for a text transport. */
    public static @NonNull String encodeString(@NonNull Object value)
            throws JsonProcessingException {
        return new String(encode(value), StandardCharsets.UTF_8);
    }

    /** Decodes a typed value; malformed input and a JSON null are rejected. */
    public static <T extends @NonNull Object> T decode(
            byte @NonNull [] payload, @NonNull TypeReference<T> type) throws IOException {
        T value = JSON.readValue(payload, type);
        if (value == null) {
            throw new IOException("Protocol value must not be null");
        }
        return value;
    }

    /** Decodes text using exactly the same rules as the byte transport. */
    public static <T extends @NonNull Object> T decode(
            @NonNull String payload, @NonNull TypeReference<T> type) throws IOException {
        return decode(payload.getBytes(StandardCharsets.UTF_8), type);
    }

    /** Reads a dynamic protocol envelope; an absent or null value is rejected. */
    public static @NonNull JsonNode readTree(@NonNull String payload) throws IOException {
        JsonNode value = JSON.readTree(payload);
        if (value == null || value.isNull() || value.isMissingNode()) {
            throw new IOException("Protocol envelope must not be null");
        }
        return value;
    }
}
