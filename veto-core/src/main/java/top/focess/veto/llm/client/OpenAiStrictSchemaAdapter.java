package top.focess.veto.llm.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;

/** Adapts canonical Veto JSON Schema to OpenAI's stricter Structured Outputs subset. */
final class OpenAiStrictSchemaAdapter {

    private OpenAiStrictSchemaAdapter() {}

    static @NonNull Adapted adapt(@NonNull JsonNode canonical) {
        JsonNode candidate = canonical.deepCopy();
        if (!normalize(candidate)) {
            return new Adapted(canonical.deepCopy(), false);
        }
        return new Adapted(candidate, true);
    }

    private static boolean normalize(@NonNull JsonNode schema) {
        if (schema.isArray()) {
            boolean compatible = true;
            for (JsonNode item : schema) {
                compatible &= normalize(item);
            }
            return compatible;
        }
        if (!schema.isObject()) {
            return true;
        }

        ObjectNode object = (ObjectNode) schema;
        boolean compatible = true;
        Iterator<JsonNode> children = object.elements();
        while (children.hasNext()) {
            compatible &= normalize(children.next());
        }

        if (!"object".equals(object.path("type").asText())) {
            return compatible;
        }

        JsonNode additional = object.get("additionalProperties");
        if (additional != null && (!additional.isBoolean() || additional.asBoolean())) {
            return false;
        }

        ObjectNode properties;
        JsonNode declaredProperties = object.get("properties");
        if (declaredProperties instanceof ObjectNode declared) {
            properties = declared;
        } else {
            properties = JsonNodeFactory.instance.objectNode();
            object.set("properties", properties);
        }

        Set<String> originallyRequired = requiredNames(object.path("required"));
        Map<String, JsonNode> replacements = new LinkedHashMap<>();
        properties
                .properties()
                .forEach(
                        entry -> {
                            if (!originallyRequired.contains(entry.getKey())) {
                                replacements.put(entry.getKey(), nullable(entry.getValue()));
                            }
                        });
        replacements.forEach(properties::set);

        ArrayNode required = JsonNodeFactory.instance.arrayNode();
        properties.fieldNames().forEachRemaining(required::add);
        object.set("required", required);
        object.put("additionalProperties", false);
        return compatible;
    }

    private static @NonNull JsonNode nullable(@NonNull JsonNode schema) {
        if (allowsNull(schema)) {
            return schema;
        }
        ObjectNode union = JsonNodeFactory.instance.objectNode();
        ArrayNode alternatives = JsonNodeFactory.instance.arrayNode();
        alternatives.add(schema.deepCopy());
        ObjectNode nullSchema = JsonNodeFactory.instance.objectNode();
        nullSchema.put("type", "null");
        alternatives.add(nullSchema);
        union.set("anyOf", alternatives);
        return union;
    }

    private static boolean allowsNull(@NonNull JsonNode schema) {
        if ("null".equals(schema.path("type").asText())) {
            return true;
        }
        for (JsonNode alternative : schema.path("anyOf")) {
            if (allowsNull(alternative)) {
                return true;
            }
        }
        return false;
    }

    private static @NonNull Set<String> requiredNames(@NonNull JsonNode required) {
        Set<String> names = new HashSet<>();
        for (JsonNode name : required) {
            names.add(name.asText());
        }
        return names;
    }

    record Adapted(@NonNull JsonNode schema, boolean strict) {}
}
