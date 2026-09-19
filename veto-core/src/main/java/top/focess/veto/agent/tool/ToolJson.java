package top.focess.veto.agent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.jspecify.annotations.NonNull;

/** Safe JSON serialization for tool success values. */
public final class ToolJson {

    private static final @NonNull ObjectMapper MAPPER = new ObjectMapper();

    private ToolJson() {}

    public static @NonNull String object(@NonNull Map<@NonNull String, ?> fields) {
        try {
            return MAPPER.writeValueAsString(fields);
        } catch (JsonProcessingException e) {
            return ToolErrors.failure(
                    ToolErrorCode.RESULT.ENCODING_FAILED,
                    "Encoding failed: could not encode the tool JSON result ("
                            + e.getMessage()
                            + ").");
        }
    }
}
