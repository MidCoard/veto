package top.focess.veto.api.agent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.NonNull;

/** Safe JSON serialization for tool success values. */
public final class ToolJson {

    private static final @NonNull ObjectMapper MAPPER = new ObjectMapper();

    private ToolJson() {}

    /**
     * Serializes a record as one JSON value or throws a structured tool failure.
     *
     * @param value record to encode
     * @return encoded JSON object text
     */
    public static @NonNull String object(@NonNull Record value) {
        return encode(value);
    }

    private static @NonNull String encode(@NonNull Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return ToolErrors.failure(
                    ToolErrorCode.RESULT.ENCODING_FAILED,
                    "Encoding failed: could not encode the tool JSON result ("
                            + e.getMessage()
                            + ").");
        }
    }
}
