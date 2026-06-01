package top.focess.veto.llm.schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import top.focess.veto.llm.core.ToolDefinition;

/**
 * Service to normalize JSON schemas for specific provider requirements. For example, OpenAI's
 * strict mode requires 'additionalProperties: false' in all objects.
 */
@Service
public class SchemaNormalizerService {

    /**
     * Builds a complete response schema for OpenAI's json_schema response format.
     *
     * @param tools the tool definitions
     * @return the OpenAI response schema
     */
    public Map<String, Object> buildOpenAIResponseSchema(List<ToolDefinition> tools) {
        Map<String, Object> properties = new LinkedHashMap<>();

        // 1. thought
        properties.put(
                "thought",
                orderedMap(
                        "type",
                        "string",
                        "description",
                        "Deep reasoning string explaining the plan"));

        // 2. call
        List<Map<String, Object>> toolSchemas = new ArrayList<>();
        for (ToolDefinition tool : tools) {
            toolSchemas.add(
                    orderedMap(
                            "type",
                            "object",
                            "properties",
                            orderedMap(
                                    "tool_name",
                                    orderedMap("const", tool.name()),
                                    "args",
                                    normalizeForOpenAI(tool.inputSchema())),
                            "required",
                            List.of("tool_name", "args"),
                            "additionalProperties",
                            false));
        }

        // Null tool call if finished
        toolSchemas.add(
                orderedMap(
                        "type",
                        "object",
                        "properties",
                        orderedMap(
                                "tool_name",
                                orderedMap("type", "null"),
                                "args",
                                orderedMap("type", "object")),
                        "required",
                        List.of("tool_name", "args"),
                        "additionalProperties",
                        false));

        properties.put("call", orderedMap("type", "object", "oneOf", toolSchemas));

        // 3. is_finished
        properties.put("is_finished", orderedMap("type", "boolean"));

        return orderedMap(
                "type",
                "object",
                "properties",
                properties,
                "required",
                List.of("thought", "call", "is_finished"),
                "additionalProperties",
                false);
    }

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

    /**
     * Maps Veto tools to Anthropic tool definitions.
     *
     * @param tools the tool definitions
     * @return the Anthropic tool definitions
     */
    public List<Map<String, Object>> mapToAnthropicTools(List<ToolDefinition> tools) {
        List<Map<String, Object>> toolSchemas = new ArrayList<>();
        for (ToolDefinition tool : tools) {
            toolSchemas.add(
                    orderedMap(
                            "type",
                            "object",
                            "properties",
                            orderedMap(
                                    "tool_name",
                                    orderedMap("const", tool.name()),
                                    "args",
                                    tool.inputSchema()),
                            "required",
                            List.of("tool_name", "args")));
        }
        toolSchemas.add(
                orderedMap(
                        "type",
                        "object",
                        "properties",
                        orderedMap(
                                "tool_name",
                                orderedMap("type", "null"),
                                "args",
                                orderedMap("type", "object")),
                        "required",
                        List.of("tool_name", "args")));

        return List.of(
                orderedMap(
                        "name",
                        "veto_pulse",
                        "description",
                        "Unified response format for Veto agent actions.",
                        "input_schema",
                        orderedMap(
                                "type",
                                "object",
                                "properties",
                                orderedMap(
                                        "thought",
                                        orderedMap(
                                                "type",
                                                "string",
                                                "description",
                                                "Deep reasoning string explaining the plan"),
                                        "call",
                                        orderedMap("type", "object", "oneOf", toolSchemas),
                                        "is_finished",
                                        orderedMap("type", "boolean")),
                                "required",
                                List.of("thought", "call", "is_finished"))));
    }

    /**
     * Maps Veto tools and response requirement to Gemini response schema.
     *
     * @param tools the tool definitions
     * @return the Gemini response schema
     */
    public Map<String, Object> buildGeminiResponseSchema(List<ToolDefinition> tools) {
        return buildOpenAIResponseSchema(tools);
    }

    private static Map<String, Object> orderedMap(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
