package top.focess.veto.providers;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Set;
import org.jspecify.annotations.NonNull;

/**
 * Selects strict native tools without weakening or rewriting their canonical argument schemas.
 * Limits and supported keywords follow Anthropic's structured-output documentation. This is a
 * conservative eligibility check, not a complete JSON Schema validator or provider capability
 * probe.
 *
 * @see <a href="https://platform.claude.com/docs/en/build-with-claude/structured-outputs">Anthropic
 *     schema limitations</a>
 */
final class AnthropicStrictToolPolicy {
    private static final @NonNull Set<String> KEYWORDS =
            Set.of(
                    "type",
                    "properties",
                    "required",
                    "additionalProperties",
                    "items",
                    "minItems",
                    "enum",
                    "const",
                    "anyOf",
                    "allOf",
                    "description",
                    "title",
                    "default",
                    "format",
                    "pattern");
    private static final @NonNull Set<String> TYPES =
            Set.of("object", "array", "string", "integer", "number", "boolean", "null");
    private static final @NonNull Set<String> FORMATS =
            Set.of(
                    "date-time",
                    "time",
                    "date",
                    "duration",
                    "email",
                    "hostname",
                    "uri",
                    "ipv4",
                    "ipv6",
                    "uuid");

    private int strictTools;
    private int optionalParameters;
    private int unionParameters;

    @NonNull Selection select(@NonNull JsonNode schema) {
        if (!"object".equals(schema.path("type").asText()))
            return new Selection(false, "tool root is not an object schema");
        var counts = new Counts();
        String incompatible = inspect(schema, counts);
        if (incompatible != null) return new Selection(false, incompatible);
        if (strictTools >= 20) return new Selection(false, "strict tool count limit");
        if (optionalParameters + counts.optional > 24)
            return new Selection(false, "optional parameter count limit");
        if (unionParameters + counts.unions > 16)
            return new Selection(false, "union parameter count limit");
        strictTools++;
        optionalParameters += counts.optional;
        unionParameters += counts.unions;
        return new Selection(true, "supported schema within request limits");
    }

    private static String inspect(@NonNull JsonNode schema, @NonNull Counts counts) {
        if (!schema.isObject()) return "non-object schema node";
        for (var field : schema.properties())
            if (!KEYWORDS.contains(field.getKey()))
                return "unsupported schema keyword: " + field.getKey();
        JsonNode type = schema.path("type");
        if (type.isArray()) {
            if (type.isEmpty()) return "empty type union";
            for (var alternative : type)
                if (!alternative.isTextual() || !TYPES.contains(alternative.asText()))
                    return "unsupported schema type";
        } else if (!type.isMissingNode() && (!type.isTextual() || !TYPES.contains(type.asText())))
            return "unsupported schema type";
        if (type.isMissingNode() && !schema.has("anyOf") && !schema.has("allOf"))
            return "unconstrained schema node";
        if (type.isArray() || schema.has("anyOf")) counts.unions++;
        if (hasType(type, "object")
                || schema.has("properties")
                || schema.has("additionalProperties")) {
            if (!schema.path("additionalProperties").isBoolean()
                    || schema.path("additionalProperties").asBoolean())
                return "object allows unspecified properties";
            var properties = schema.path("properties");
            if (!properties.isMissingNode() && !properties.isObject())
                return "invalid properties schema";
            var required = schema.path("required");
            if (!required.isMissingNode() && !required.isArray()) return "invalid required schema";
            var names = new HashSet<String>();
            for (var name : required) {
                if (!name.isTextual() || !properties.has(name.asText()))
                    return "invalid required property";
                names.add(name.asText());
            }
            for (var property : properties.properties()) {
                if (!names.contains(property.getKey())) counts.optional++;
                String incompatible = inspect(property.getValue(), counts);
                if (incompatible != null) return incompatible;
            }
        }
        if (schema.has("items")) {
            String incompatible = inspect(schema.path("items"), counts);
            if (incompatible != null) return incompatible;
        } else if (hasType(type, "array")) return "array has no item schema";
        if (schema.has("minItems")) {
            var minimum = schema.path("minItems");
            if (!minimum.isIntegralNumber()
                    || !minimum.canConvertToInt()
                    || minimum.asInt() < 0
                    || minimum.asInt() > 1) return "unsupported minItems constraint";
        }
        if (schema.has("enum")) {
            var allowed = schema.path("enum");
            if (!allowed.isArray() || allowed.isEmpty()) return "invalid enum schema";
            for (var value : allowed) if (value.isContainerNode()) return "complex enum value";
        }
        if (schema.path("const").isContainerNode()) return "complex const value";
        if (schema.has("format") && !FORMATS.contains(schema.path("format").asText()))
            return "unsupported string format";
        if (schema.has("pattern") && !simplePattern(schema.path("pattern")))
            return "unsupported regular expression feature";
        for (String keyword : Set.of("anyOf", "allOf")) {
            if (!schema.has(keyword)) continue;
            var alternatives = schema.path(keyword);
            if (!alternatives.isArray() || alternatives.isEmpty())
                return "invalid schema composition";
            for (var alternative : alternatives) {
                String incompatible = inspect(alternative, counts);
                if (incompatible != null) return incompatible;
            }
        }
        return null;
    }

    private static boolean hasType(@NonNull JsonNode type, @NonNull String name) {
        if (name.equals(type.asText())) return true;
        for (var alternative : type) if (name.equals(alternative.asText())) return true;
        return false;
    }

    private static boolean simplePattern(@NonNull JsonNode pattern) {
        if (!pattern.isTextual()) return false;
        String text = pattern.asText();
        // Unknown advanced regex constructs stay non-strict; the original pattern is retained.
        if (text.contains("(?") || text.contains("{") || text.contains("}")) return false;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) != '\\') continue;
            if (++i == text.length()) return false;
            char escaped = text.charAt(i);
            if (Character.isLetterOrDigit(escaped) && "dDwWsSnrt".indexOf(escaped) < 0)
                return false;
        }
        try {
            java.util.regex.Pattern.compile(text);
            return true;
        } catch (java.util.regex.PatternSyntaxException invalid) {
            return false;
        }
    }

    private static final class Counts {
        int optional;
        int unions;
    }

    record Selection(boolean strict, @NonNull String reason) {}
}
