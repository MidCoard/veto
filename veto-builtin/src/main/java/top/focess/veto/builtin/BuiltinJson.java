package top.focess.veto.builtin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.JsonValues;

/**
 * Shared builtin presentation encoding. The mapper is configured once before publication and is
 * never exposed or reconfigured, so callers may encode concurrently.
 */
public final class BuiltinJson {
    private static final @NonNull ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private BuiltinJson() {}

    /** Converts a presentation value to the bounded plugin response contract. */
    public static @NonNull JsonValue value(@NonNull Object value) {
        return JsonValues.from(MAPPER.valueToTree(value));
    }

    /** Creates an independent object tree for feature-owned metadata decoration. */
    public static @NonNull ObjectNode object(@NonNull Object value) {
        JsonNode tree = MAPPER.valueToTree(value);
        if (tree instanceof ObjectNode object) return object;
        throw new IllegalArgumentException("Expected an object presentation value");
    }

    /** Encodes a tool result; the owning tool decides how to report an encoding failure. */
    public static @NonNull String encode(@NonNull Object value) throws JsonProcessingException {
        return MAPPER.writeValueAsString(value);
    }
}
