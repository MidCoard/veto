package top.focess.veto.api.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import org.jspecify.annotations.NonNull;

public interface InputSchemaSource {
    @NonNull JsonNode schema();
}
