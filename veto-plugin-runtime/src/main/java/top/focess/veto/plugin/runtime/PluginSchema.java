package top.focess.veto.plugin.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Set;
import org.jspecify.annotations.NonNull;

/** Deliberately small schema vocabulary. Unknown constraints are rejected, never ignored. */
public final class PluginSchema {
    private PluginSchema() {}

    public static void check(@NonNull JsonNode schema) {
        check(schema, 0);
    }

    private static void check(@NonNull JsonNode schema, int depth) {
        require(depth <= 16 && schema.isObject());
        fields(
                schema,
                Set.of(
                        "type",
                        "description",
                        "properties",
                        "required",
                        "additionalProperties",
                        "items",
                        "enum"));
        String type = schema.path("type").asText();
        require(
                Set.of("object", "array", "string", "integer", "number", "boolean", "null")
                        .contains(type));
        if (schema.has("description")) require(schema.path("description").isTextual());
        if (schema.has("enum"))
            require(schema.path("enum").isArray() && !schema.path("enum").isEmpty());
        if (type.equals("object")) {
            require(schema.path("properties").isObject());
            require(
                    schema.path("additionalProperties").isBoolean()
                            && !schema.path("additionalProperties").asBoolean());
            var properties = schema.path("properties");
            require(properties.size() <= 64);
            for (var entry : properties.properties()) {
                require(entry.getKey().matches("[A-Za-z_][A-Za-z0-9_]{0,63}"));
                check(entry.getValue(), depth + 1);
            }
            if (schema.has("required")) {
                require(schema.path("required").isArray());
                Set<String> names = new HashSet<>();
                for (var name : schema.path("required")) {
                    require(
                            name.isTextual()
                                    && properties.has(name.asText())
                                    && names.add(name.asText()));
                }
            }
        } else {
            require(
                    !schema.has("properties")
                            && !schema.has("required")
                            && !schema.has("additionalProperties"));
        }
        if (type.equals("array")) check(schema.path("items"), depth + 1);
        else require(!schema.has("items"));
    }

    public static void validate(@NonNull JsonNode schema, @NonNull JsonNode value) {
        boolean matches =
                switch (schema.path("type").asText()) {
                    case "object" -> value.isObject();
                    case "array" -> value.isArray();
                    case "string" -> value.isTextual();
                    case "integer" -> value.isIntegralNumber();
                    case "number" -> value.isNumber();
                    case "boolean" -> value.isBoolean();
                    case "null" -> value.isNull();
                    default -> false;
                };
        require(matches);
        if (schema.has("enum")) {
            boolean found = false;
            for (var candidate : schema.path("enum"))
                if (candidate.equals(value)) {
                    found = true;
                    break;
                }
            require(found);
        }
        if (value.isObject()) {
            JsonNode properties = schema.path("properties");
            for (var key : schema.path("required")) require(value.has(key.asText()));
            for (var entry : value.properties()) {
                require(properties.has(entry.getKey()));
                validate(properties.path(entry.getKey()), entry.getValue());
            }
        } else if (value.isArray()) {
            for (var entry : value) validate(schema.path("items"), entry);
        }
    }

    static void fields(@NonNull JsonNode value, @NonNull Set<String> allowed) {
        require(value.isObject());
        for (var field : value.properties()) require(allowed.contains(field.getKey()));
    }

    static void require(boolean condition) {
        if (!condition) throw new IllegalArgumentException("Invalid plugin contract or value");
    }
}
