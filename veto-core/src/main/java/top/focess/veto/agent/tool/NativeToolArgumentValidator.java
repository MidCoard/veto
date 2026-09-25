package top.focess.veto.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.lang.reflect.AnnotatedArrayType;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.RequiredWhen;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.agent.tool.ToolErrorCode;
import top.focess.veto.api.agent.tool.ToolExecutionException;
import top.focess.veto.api.agent.tool.ToolInputSchema;
import top.focess.veto.api.agent.tool.ToolResultFormat;
import top.focess.veto.api.agent.tool.ToolResultStatus;

/** Validates native-tool arguments against the same record schema advertised to the model. */
public final class NativeToolArgumentValidator {

    private NativeToolArgumentValidator() {}

    public static void validate(
            @NonNull String toolName, @NonNull JsonNode arguments, @NonNull Class<?> argsClass) {
        validate(toolName, arguments, argsClass, Set.of());
    }

    public static void validate(
            @NonNull String toolName,
            @NonNull JsonNode arguments,
            @NonNull Class<?> argsClass,
            @NonNull Set<String> deferredPaths) {
        JsonNode schema = ToolSchemaCompiler.compileFromRecord(argsClass);
        List<String> issues = new ArrayList<>();
        validateNode(
                arguments,
                schema,
                "",
                issues,
                !argsClass.isAnnotationPresent(ToolDocs.nonNullClass(ToolInputSchema.class)),
                deferredPaths);
        validateConditionalRequirements(arguments, argsClass, "", issues);
        throwIfInvalid(toolName, schema, issues);
    }

    /** Validates a contextual schema, including tool catalog constraints captured for a request. */
    public static void validateAgainstSchema(
            @NonNull String toolName, @NonNull JsonNode arguments, @NonNull JsonNode schema) {
        validateAgainstSchema(toolName, arguments, schema, Set.of());
    }

    /** Defers only explicitly named preflight paths; execution always validates every argument. */
    public static void validateAgainstSchema(
            @NonNull String toolName,
            @NonNull JsonNode arguments,
            @NonNull JsonNode schema,
            @NonNull Set<String> deferredPaths) {
        List<String> issues = new ArrayList<>();
        JsonNode resolved = resolveReferences(schema, schema, new LinkedHashSet<>(), issues, 0);
        validateNode(arguments, resolved, "", issues, false, deferredPaths);
        throwIfInvalid(toolName, schema, issues);
    }

    private static @NonNull JsonNode resolveReferences(
            @NonNull JsonNode node,
            @NonNull JsonNode root,
            @NonNull Set<String> resolving,
            @NonNull List<String> issues,
            int depth) {
        if (depth > 64) {
            issues.add("tool schema reference depth exceeds 64");
            return node;
        }
        JsonNode reference = node.path("$ref");
        if (reference.isTextual()) {
            String pointer = reference.asText();
            if (!pointer.startsWith("#/")) {
                issues.add("tool schema contains an unsupported reference");
                return node;
            }
            if (!resolving.add(pointer)) {
                issues.add("tool schema contains a cyclic reference");
                return node;
            }
            JsonNode target = root.at(pointer.substring(1));
            if (target.isMissingNode()) {
                issues.add("tool schema contains an unresolved reference");
                resolving.remove(pointer);
                return node;
            }
            JsonNode resolved = resolveReferences(target, root, resolving, issues, depth + 1);
            resolving.remove(pointer);
            return resolved;
        }
        if (node.isObject()) {
            ObjectNode resolved = ((ObjectNode) node).deepCopy();
            node.properties()
                    .forEach(
                            entry ->
                                    resolved.set(
                                            entry.getKey(),
                                            resolveReferences(
                                                    entry.getValue(),
                                                    root,
                                                    resolving,
                                                    issues,
                                                    depth + 1)));
            return resolved;
        }
        if (node.isArray()) {
            ArrayNode resolved = ((ArrayNode) node).deepCopy();
            for (int index = 0; index < node.size(); index++)
                resolved.set(
                        index,
                        resolveReferences(node.path(index), root, resolving, issues, depth + 1));
            return resolved;
        }
        return node;
    }

    private static void throwIfInvalid(
            @NonNull String toolName, @NonNull JsonNode schema, @NonNull List<String> issues) {
        if (!issues.isEmpty()) {
            List<String> expected = fieldNames(schema.path("properties"));
            throw new ToolExecutionException(
                    ToolResultStatus.FAILURE,
                    ToolResultFormat.PLAINTEXT,
                    ToolErrorCode.VALIDATION.INVALID_ARGUMENTS,
                    "Invalid arguments for "
                            + toolName
                            + ": "
                            + String.join("; ", issues)
                            + ". Expected parameters: "
                            + expected);
        }
    }

    private static void validateConditionalRequirements(
            @NonNull JsonNode arguments,
            @NonNull Class<?> argsClass,
            @NonNull String path,
            @NonNull List<String> issues) {
        if (!arguments.isObject()) {
            return;
        }
        for (RecordComponent component : argsClass.getRecordComponents()) {
            JsonNode nestedValue = arguments.get(component.getName());
            if (nestedValue != null) {
                validateAnnotatedValue(
                        nestedValue,
                        component.getAnnotatedType(),
                        childPath(path, component.getName()),
                        issues);
            }
            RequiredWhen requiredWhen =
                    component.getAnnotation(ToolDocs.nonNullClass(RequiredWhen.class));
            if (requiredWhen == null) {
                continue;
            }
            JsonNode discriminator = arguments.get(requiredWhen.field());
            if (!matchesAny(discriminator, requiredWhen.values())) {
                continue;
            }
            JsonNode value = arguments.get(component.getName());
            String componentPath = childPath(path, component.getName());
            String condition =
                    " when '"
                            + childPath(path, requiredWhen.field())
                            + "' is '"
                            + discriminator.asText()
                            + "'";
            if (value == null || value.isNull()) {
                issues.add("missing required parameter '" + componentPath + "'" + condition);
            } else if (requiredWhen.rejectBlank()
                    && value.isTextual()
                    && value.asText().isBlank()) {
                issues.add("parameter '" + componentPath + "' must not be blank" + condition);
            }
        }
    }

    private static void validateAnnotatedValue(
            @NonNull JsonNode value,
            @NonNull AnnotatedType type,
            @NonNull String path,
            @NonNull List<String> issues) {
        if (value.isNull()) {
            if (type.isAnnotationPresent(ToolDocs.nonNullClass(NonNull.class))) {
                issues.add("parameter '" + path + "' must not be null");
            }
            return;
        }
        if (type.getType() instanceof Class<?> recordClass && recordClass.isRecord()) {
            validateConditionalRequirements(value, recordClass, path, issues);
        } else if (value.isArray()) {
            AnnotatedType elementType = null;
            if (type instanceof AnnotatedArrayType array) {
                elementType = array.getAnnotatedGenericComponentType();
            } else if (type instanceof AnnotatedParameterizedType collection) {
                elementType = collection.getAnnotatedActualTypeArguments()[0];
            }
            if (elementType != null) {
                for (int i = 0; i < value.size(); i++) {
                    validateAnnotatedValue(value.get(i), elementType, path + "[" + i + "]", issues);
                }
            }
        }
    }

    private static boolean matchesAny(JsonNode actual, String @NonNull [] expectedValues) {
        if (actual == null || actual.isNull() || !actual.isValueNode()) {
            return false;
        }
        String serialized = actual.asText();
        for (String expected : expectedValues) {
            if (expected.equals(serialized)) {
                return true;
            }
        }
        return false;
    }

    private static void validateNode(
            @NonNull JsonNode originalValue,
            @NonNull JsonNode schema,
            @NonNull String path,
            @NonNull List<String> issues,
            boolean javaNulls,
            @NonNull Set<String> deferredPaths) {
        if (schema.isBoolean()) {
            if (!schema.asBoolean())
                issues.add("parameter '" + displayPath(path) + "' is forbidden by its schema");
            return;
        }
        JsonNode value = originalValue;
        if (deferredPaths.contains(path)) return;
        var variants = schema.path("anyOf");
        if (variants.isArray()) {
            List<JsonNode> choices = new ArrayList<>();
            variants.forEach(choices::add);
            validateVariants(value, choices, path, issues, javaNulls, deferredPaths);
        }
        JsonNode type = schema.path("type");
        if ((path.isEmpty() && !value.isObject()) || !matchesType(value, type, javaNulls)) {
            issues.add(
                    "parameter '"
                            + displayPath(path)
                            + "' must be "
                            + type
                            + ", got "
                            + actualType(value));
            return;
        }
        JsonNode allowed = schema.path("enum");
        if (allowed.isArray() && !allowed.isEmpty() && !containsValue(allowed, value)) {
            // Diagnostics are replayed to the model and persisted; describe the constraint
            // without copying a possibly sensitive rejected value into another record.
            issues.add("parameter '" + displayPath(path) + "' must be one of " + allowed);
            return;
        }
        if (schema.has("const") && !schema.path("const").equals(value)) {
            issues.add("parameter '" + displayPath(path) + "' must equal " + schema.path("const"));
            return;
        }
        if (value.isNull()) return;
        if (value.isObject()) {
            JsonNode properties = schema.path("properties");
            for (String name : fieldNames(value)) {
                if (schema.has("propertyNames"))
                    validateNode(
                            TextNode.valueOf(name),
                            schema.path("propertyNames"),
                            childPath(path, name),
                            issues,
                            javaNulls,
                            Set.of());
                boolean matchedProperty = properties.has(name);
                for (var pattern : schema.path("patternProperties").properties()) {
                    if (!java.util.regex.Pattern.compile(pattern.getKey()).matcher(name).find())
                        continue;
                    matchedProperty = true;
                    validateNode(
                            value.path(name),
                            pattern.getValue(),
                            childPath(path, name),
                            issues,
                            javaNulls,
                            deferredPaths);
                }
                if (!matchedProperty) {
                    var additional = schema.path("additionalProperties");
                    if (additional.isBoolean() && !additional.asBoolean())
                        issues.add("unknown parameter '" + childPath(path, name) + "'");
                    else if (additional.isObject())
                        validateNode(
                                value.path(name),
                                additional,
                                childPath(path, name),
                                issues,
                                javaNulls,
                                deferredPaths);
                }
            }
            for (JsonNode required : schema.path("required")) {
                String name = required.asText();
                if (!value.has(name) || (javaNulls && value.path(name).isNull()))
                    issues.add("missing required parameter '" + childPath(path, name) + "'");
            }
            for (String name : fieldNames(properties)) {
                if (value.has(name) && !(javaNulls && value.path(name).isNull()))
                    validateNode(
                            value.path(name),
                            properties.path(name),
                            childPath(path, name),
                            issues,
                            javaNulls,
                            deferredPaths);
            }
        } else if (value.isArray()) {
            if (schema.has("minItems") && value.size() < schema.path("minItems").asInt())
                issues.add("parameter '" + displayPath(path) + "' has too few items");
            if (schema.has("maxItems") && value.size() > schema.path("maxItems").asInt())
                issues.add("parameter '" + displayPath(path) + "' has too many items");
            for (int i = 0; i < value.size(); i++)
                validateNode(
                        value.path(i),
                        schema.path("items"),
                        path + "[" + i + "]",
                        issues,
                        javaNulls,
                        deferredPaths);
        } else if (value.isTextual()) {
            String text = value.asText();
            int length = text.codePointCount(0, text.length());
            if (schema.has("minLength") && length < schema.path("minLength").asInt())
                issues.add("parameter '" + displayPath(path) + "' is too short");
            if (schema.has("maxLength") && length > schema.path("maxLength").asInt())
                issues.add("parameter '" + displayPath(path) + "' is too long");
            if (schema.has("pattern")
                    && !java.util.regex.Pattern.compile(schema.path("pattern").asText())
                            .matcher(text)
                            .find())
                issues.add(
                        "parameter '"
                                + displayPath(path)
                                + "' does not match its required pattern");
        } else if (value.isNumber()) {
            if (schema.has("minimum")
                    && value.decimalValue().compareTo(schema.path("minimum").decimalValue()) < 0)
                issues.add("parameter '" + displayPath(path) + "' is below its minimum");
            if (schema.has("maximum")
                    && value.decimalValue().compareTo(schema.path("maximum").decimalValue()) > 0)
                issues.add("parameter '" + displayPath(path) + "' exceeds its maximum");
        }
    }

    /** Use declared discriminators, never error count, to select an object variant. */
    private static void validateVariants(
            @NonNull JsonNode value,
            @NonNull List<JsonNode> variants,
            @NonNull String path,
            @NonNull List<String> issues,
            boolean javaNulls,
            @NonNull Set<String> deferredPaths) {
        if (value.isObject() && variants.size() > 1) {
            for (String key : fieldNames(variants.getFirst().path("properties"))) {
                var allowed = new LinkedHashSet<JsonNode>();
                boolean discriminator = true;
                for (var variant : variants) {
                    var choices = variant.path("properties").path(key).path("enum");
                    if (!choices.isArray()
                            || choices.isEmpty()
                            || !containsValue(variant.path("required"), TextNode.valueOf(key))) {
                        discriminator = false;
                        break;
                    }
                    choices.forEach(allowed::add);
                }
                if (!discriminator || allowed.size() < 2) continue;
                if (!value.has(key)) {
                    String at = childPath(path, key);
                    issues.add(
                            "missing required discriminator '"
                                    + at
                                    + "'; expected one of "
                                    + allowed
                                    + ". Put it directly on '"
                                    + displayPath(path)
                                    + "', alongside that variant's fields");
                    // Locate misplaced discriminator fields as a diagnostic only; never repair or
                    // execute a guessed variant.
                    for (String child : fieldNames(value)) {
                        if (value.path(child).isObject() && value.path(child).has(key))
                            issues.add(
                                    "found '"
                                            + childPath(childPath(path, child), key)
                                            + "'; it does not define '"
                                            + at
                                            + "'");
                    }
                    return;
                }
                JsonNode selectedValue = value.path(key);
                if (deferredPaths.contains(childPath(path, key))) continue;
                List<JsonNode> matching = new ArrayList<>();
                for (var variant : variants)
                    if (containsValue(
                            variant.path("properties").path(key).path("enum"), selectedValue))
                        matching.add(variant);
                if (matching.isEmpty()) {
                    issues.add(
                            "discriminator '"
                                    + childPath(path, key)
                                    + "' must be one of "
                                    + allowed);
                    return;
                }
                if (matching.size() < variants.size()) {
                    validateVariants(value, matching, path, issues, javaNulls, deferredPaths);
                    return;
                }
            }
        }
        if (variants.size() == 1) {
            validateNode(value, variants.getFirst(), path, issues, javaNulls, deferredPaths);
            return;
        }
        List<List<String>> failures = new ArrayList<>();
        for (var variant : variants) {
            List<String> candidate = new ArrayList<>();
            validateNode(value, variant, path, candidate, javaNulls, deferredPaths);
            if (candidate.isEmpty()) return;
            failures.add(candidate);
        }
        issues.add(
                "parameter '"
                        + displayPath(path)
                        + "' does not match any allowed shape: "
                        + failures);
    }

    private static boolean matchesType(
            @NonNull JsonNode value, @NonNull JsonNode expected, boolean javaNulls) {
        if (expected.isArray()) {
            for (var type : expected) if (matchesType(value, type, javaNulls)) return true;
            return false;
        }
        if (expected.isMissingNode()) return true;
        String name = expected.asText();
        if (name.equals("null")) return value.isNull();
        if (value.isNull()) return javaNulls;
        return switch (name) {
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
}
