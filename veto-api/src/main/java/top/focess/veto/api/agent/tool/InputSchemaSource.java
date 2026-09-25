package top.focess.veto.api.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import org.jspecify.annotations.NonNull;

/**
 * Supplies a complete JSON Schema for a tool whose schema is not derived from its argument type.
 */
public interface InputSchemaSource {
    /**
     * Returns the model-visible input schema. Callers must treat the returned tree as read-only.
     *
     * @return the complete JSON Schema root
     */
    @NonNull JsonNode schema();
}
