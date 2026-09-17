package top.focess.veto.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.Set;
import org.jspecify.annotations.NonNull;

/**
 * Makes a tool-owned schema safe to embed in another JSON document. Ordinary local JSON-pointer
 * references are inlined against the tool's original root, never the enclosing plan root.
 * Recursive, external and anchor references remain the remote tool's validation responsibility:
 * only that unresolved reference is omitted; its sibling constraints are retained.
 *
 * <p>This is reference normalization, not a complete JSON Schema validator. It does not fetch
 * schemas or interpret separate resource scopes introduced by nested {@code $id} declarations.
 */
public final class ToolSchemaReferences {
    private static final @NonNull ObjectMapper MAPPER = new ObjectMapper();
    private static final @NonNull Set<String> SCHEMA_MAPS =
            Set.of("properties", "patternProperties", "dependentSchemas");
    private static final @NonNull Set<String> SCHEMA_ARRAYS =
            Set.of("anyOf", "oneOf", "allOf", "prefixItems");
    private static final @NonNull Set<String> SCHEMA_VALUES =
            Set.of(
                    "items",
                    "additionalItems",
                    "additionalProperties",
                    "unevaluatedProperties",
                    "unevaluatedItems",
                    "contains",
                    "propertyNames",
                    "not",
                    "if",
                    "then",
                    "else");

    private ToolSchemaReferences() {}

    public static @NonNull JsonNode inlineForEmbedding(@NonNull JsonNode schema) {
        return normalize(schema, schema, new HashSet<>(), false);
    }

    private static @NonNull JsonNode normalize(
            @NonNull JsonNode original,
            @NonNull JsonNode root,
            @NonNull Set<String> active,
            boolean nestedResource) {
        if (!original.isObject()) return original.deepCopy();
        ObjectNode result = original.deepCopy();
        boolean separateResource = nestedResource || (original != root && original.has("$id"));
        // Definitions are expanded at their use sites. Keeping root-relative references inside
        // unused definitions would accidentally address the enclosing submit_plan document.
        result.remove(
                java.util.List.of(
                        "$defs",
                        "definitions",
                        "$id",
                        "$schema",
                        "$anchor",
                        "$dynamicAnchor",
                        "$dynamicRef",
                        "$ref"));
        for (String keyword : SCHEMA_MAPS) {
            if (!original.path(keyword).isObject()) continue;
            ObjectNode children = MAPPER.createObjectNode();
            original.path(keyword)
                    .properties()
                    .forEach(
                            entry ->
                                    children.set(
                                            entry.getKey(),
                                            normalize(
                                                    entry.getValue(),
                                                    root,
                                                    active,
                                                    separateResource)));
            result.set(keyword, children);
        }
        for (String keyword : SCHEMA_ARRAYS) {
            if (!original.path(keyword).isArray()) continue;
            var children = MAPPER.createArrayNode();
            for (var child : original.path(keyword))
                children.add(normalize(child, root, active, separateResource));
            result.set(keyword, children);
        }
        for (String keyword : SCHEMA_VALUES) {
            if (original.path(keyword).isObject() || original.path(keyword).isBoolean())
                result.set(
                        keyword, normalize(original.path(keyword), root, active, separateResource));
            else if (keyword.equals("items") && original.path(keyword).isArray()) {
                var children = MAPPER.createArrayNode();
                for (var child : original.path(keyword))
                    children.add(normalize(child, root, active, separateResource));
                result.set(keyword, children);
            }
        }
        String reference = original.path("$ref").asText("");
        if (separateResource
                || !(reference.equals("#") || reference.startsWith("#/"))
                || !active.add(reference)) return result;
        JsonNode target;
        try {
            target = reference.equals("#") ? root : root.at(reference.substring(1));
        } catch (IllegalArgumentException ignored) {
            active.remove(reference);
            return result;
        }
        if (target.isMissingNode()) {
            active.remove(reference);
            return result;
        }
        JsonNode resolved = normalize(target, root, active, separateResource);
        active.remove(reference);
        if (result.isEmpty()) return resolved;
        if (resolved.isObject()) {
            boolean conflict = false;
            for (var entry : resolved.properties()) {
                if (result.has(entry.getKey())
                        && !result.path(entry.getKey()).equals(entry.getValue())) {
                    conflict = true;
                    break;
                }
            }
            if (!conflict) {
                resolved.properties()
                        .forEach(entry -> result.set(entry.getKey(), entry.getValue()));
                return result;
            }
        }
        // Both the referenced schema and its siblings apply; neither overwrites the other.
        // Keep siblings at this level as well, so a partial local validator still checks them.
        ObjectNode siblings = result.deepCopy();
        result.putArray("allOf").add(resolved).add(siblings);
        return result;
    }
}
