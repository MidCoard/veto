package top.focess.veto.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import org.jspecify.annotations.NonNull;

/** Validates native-tool arguments against the same record schema advertised to the model. */
final class NativeToolArgumentValidator {

    private NativeToolArgumentValidator() {}

    static void validate(
            @NonNull String toolName, @NonNull JsonNode arguments, @NonNull Class<?> argsClass) {
        JsonNode schema = ToolSchemaCompiler.compileFromRecord(argsClass);
        List<String> issues = new ArrayList<>();
        validateNode(arguments, schema, "", issues);
        if (!issues.isEmpty()) {
            List<String> expected = fieldNames(schema.path("properties"));
            throw new InvalidArgumentsException(
                    "Invalid arguments for "
                            + toolName
                            + ": "
                            + String.join("; ", issues)
                            + ". Expected parameters: "
                            + expected);
        }
    }

    private static void validateNode(
            @NonNull JsonNode value,
            @NonNull JsonNode schema,
            @NonNull String path,
            @NonNull List<String> issues) {
        String expectedType = schema.path("type").asText();
        if (!matchesType(value, expectedType)) {
            issues.add(
                    "parameter '"
                            + displayPath(path)
                            + "' must be "
                            + expectedType
                            + ", got "
                            + actualType(value));
            return;
        }
        JsonNode allowed = schema.path("enum");
        if (allowed.isArray() && !allowed.isEmpty() && !containsValue(allowed, value)) {
            issues.add(
                    "parameter '"
                            + displayPath(path)
                            + "' must be one of "
                            + allowed
                            + ", got "
                            + value);
            return;
        }

        if ("object".equals(expectedType)) {
            JsonNode properties = schema.path("properties");
            List<String> actualNames = fieldNames(value);
            for (String name : actualNames) {
                if (!properties.has(name)) {
                    issues.add("unknown parameter '" + childPath(path, name) + "'");
                }
            }
            for (JsonNode required : schema.path("required")) {
                String name = required.asText();
                if (!value.has(name) || value.get(name).isNull()) {
                    issues.add("missing required parameter '" + childPath(path, name) + "'");
                }
            }
            for (String name : fieldNames(properties)) {
                if (value.has(name) && !value.get(name).isNull()) {
                    validateNode(
                            value.get(name), properties.get(name), childPath(path, name), issues);
                }
            }
        } else if ("array".equals(expectedType)) {
            JsonNode itemSchema = schema.path("items");
            for (int i = 0; i < value.size(); i++) {
                validateNode(value.get(i), itemSchema, path + "[" + i + "]", issues);
            }
        }
    }

    private static boolean matchesType(@NonNull JsonNode value, @NonNull String expectedType) {
        if (value.isNull()) return true;
        return switch (expectedType) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            default -> true;
        };
    }

    private static @NonNull String actualType(@NonNull JsonNode value) {
        if (value.isObject()) return "object";
        if (value.isArray()) return "array";
        if (value.isTextual()) return "string";
        if (value.isIntegralNumber()) return "integer";
        if (value.isNumber()) return "number";
        if (value.isBoolean()) return "boolean";
        if (value.isNull()) return "null";
        return value.getNodeType().name().toLowerCase();
    }

    private static boolean containsValue(@NonNull JsonNode allowed, @NonNull JsonNode value) {
        for (JsonNode candidate : allowed) {
            if (candidate.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static @NonNull List<String> fieldNames(@NonNull JsonNode node) {
        List<String> names = new ArrayList<>();
        Iterator<String> iterator = node.fieldNames();
        iterator.forEachRemaining(names::add);
        Collections.sort(names);
        return names;
    }

    private static @NonNull String childPath(@NonNull String parent, @NonNull String child) {
        return parent.isEmpty() ? child : parent + "." + child;
    }

    private static @NonNull String displayPath(@NonNull String path) {
        return path.isEmpty() ? "arguments" : path;
    }

    @SuppressWarnings("serial")
    static final class InvalidArgumentsException extends IllegalArgumentException {
        InvalidArgumentsException(@NonNull String message) {
            super(message);
        }
    }
}
