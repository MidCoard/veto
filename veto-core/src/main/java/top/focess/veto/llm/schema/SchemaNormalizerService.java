package top.focess.veto.llm.schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import top.focess.veto.llm.core.ToolDefinition;

/**
 * Normalizes JSON schemas for provider-specific requirements (e.g. OpenAI's strict mode requires
 * {@code additionalProperties: false} in all objects).
 *
 * <p>The per-turn {@code veto_pulse} response schema is now built by the {@link
 * top.focess.veto.agent.translation.CapabilityTranslator} (Part 1 loop / Part 5) and carried on the
 * {@link top.focess.veto.llm.core.VetoRequest}; the old single-{@code call} veto_pulse builders
 * that lived here are retired.
 */
@Service
public class SchemaNormalizerService {

    /**
     * Normalizes a JSON schema to be compatible with OpenAI's strict mode.
     *
     * @param schema the original schema
     * @return the normalized schema
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> normalizeForOpenAI(Map<String, Object> schema) {
        if (schema == null) {
            return Map.of();
        }

        Map<String, Object> normalized = new LinkedHashMap<>(schema);
        String type = (String) normalized.get("type");

        if ("object".equals(type)) {
            normalized.put("additionalProperties", false);
            Map<String, Object> properties = (Map<String, Object>) normalized.get("properties");
            if (properties != null) {
                Map<String, Object> normalizedProperties = new LinkedHashMap<>();
                List<String> requiredFields = new ArrayList<>();
                for (Map.Entry<String, Object> entry : properties.entrySet()) {
                    normalizedProperties.put(
                            entry.getKey(),
                            normalizeForOpenAI((Map<String, Object>) entry.getValue()));
                    requiredFields.add(entry.getKey());
                }
                normalized.put("properties", normalizedProperties);
                normalized.put("required", requiredFields);
            } else {
                normalized.put("properties", Map.of());
                normalized.put("required", List.of());
            }
        } else if (normalized.containsKey("properties")) {
            normalized.put("type", "object");
            return normalizeForOpenAI(normalized);
        }

        return normalized;
    }

    /** Normalizes a flat {@link ToolDefinition}'s input schema for OpenAI strict mode. */
    public Map<String, Object> normalizeToolForOpenAI(ToolDefinition tool) {
        return normalizeForOpenAI(tool.inputSchema());
    }
}
