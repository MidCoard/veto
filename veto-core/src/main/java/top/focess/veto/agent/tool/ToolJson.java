package top.focess.veto.agent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.NonNull;

/** Safe JSON serialization for tool success values. */
public final class ToolJson {

    private static final @NonNull ObjectMapper MAPPER = new ObjectMapper();

    private ToolJson() {}

    public static @NonNull String object(@NonNull Record value) {
        return encode(value);
    }

    private static @NonNull String encode(@NonNull Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return ToolErrors.failure("Could not encode tool JSON result: " + e.getMessage());
        }
    }
}
