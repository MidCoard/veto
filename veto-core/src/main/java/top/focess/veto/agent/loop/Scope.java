package top.focess.veto.agent.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.agent.tool.ToolResult;
import top.focess.veto.llm.core.VetoResponse;

/**
 * The engine-internal Scope — a derived projection of action outputs, auto-populated by the harness
 * . The model never reads/writes the Scope directly (it reads prior results from the conversation);
 * the engine consumes it for programmatic checks, transitions, and escape.
 *
 * <p>Lexically scoped: program-global by default; a child scope reads through to its parent.
 * Missing keys are values, not errors — reading an unset slot returns the {@link #UNDEFINED}
 * sentinel so {@code empty:}/{@code equals: undefined} checks branch on it.
 */
public class Scope {

    /** Sentinel for unset slots — a value, not an error (checks branch on it). */
    public static final @NonNull Object UNDEFINED = new Object();

    private final @NonNull Map<String, Object> bindings = new HashMap<>();
    private final Scope parent;
    private final @NonNull ObjectMapper objectMapper;

    public Scope(@NonNull ObjectMapper objectMapper) {
        this(objectMapper, null);
    }

    public Scope(@NonNull ObjectMapper objectMapper, Scope parent) {
        this.objectMapper = objectMapper;
        this.parent = parent;
    }

    /** Read-through to parent. Missing key returns {@link #UNDEFINED}, not an error. */
    public @NonNull Object get(String var) {
        if (var == null) {
            return UNDEFINED;
        }
        String key = var.startsWith("$") ? var.substring(1) : var;
        if (bindings.containsKey(key)) {
            return bindings.get(key);
        }
        if (parent != null) {
            return parent.get(var);
        }
        return UNDEFINED;
    }

    public boolean contains(String var) {
        String key = var == null ? "" : (var.startsWith("$") ? var.substring(1) : var);
        return bindings.containsKey(key) || (parent != null && parent.contains(var));
    }

    public void put(@NonNull String var, @NonNull Object value) {
        String key = var.startsWith("$") ? var.substring(1) : var;
        bindings.put(key, value);
    }

    /**
     * Resolves a {@code $var|literal} spec to a concrete value (literal if no {@code $} prefix).
     */
    public @NonNull Scope child() {
        return new Scope(objectMapper, this);
    }

    public @NonNull Object resolveValue(Object spec) {
        if (spec == null) return NullNode.getInstance();
        if (spec instanceof JsonNode node) {
            if (node.isTextual()) return resolveValue(node.asText());
            if (node.isNull()) return node;
            if (node.isNumber()) return node.numberValue();
            if (node.isBoolean()) return node.booleanValue();
            if (node.isArray()) {
                List<Object> values = new ArrayList<>();
                node.forEach(value -> values.add(resolveValue(value)));
                return values;
            }
            Map<String, Object> values = new LinkedHashMap<>();
            node.properties().forEach(e -> values.put(e.getKey(), resolveValue(e.getValue())));
            return values;
        }
        if (spec instanceof String text) {
            if (text.startsWith("$$")) return text.substring(1);
            if (text.startsWith("$")) {
                Object value = get(text);
                if (value == UNDEFINED)
                    throw new IllegalArgumentException("Unbound guided input: " + text);
                return value;
            }
        }
        return spec;
    }

    /**
     * Replace complete variable tokens once; never interpolate text introduced by a replacement.
     */
    public @NonNull String resolveVars(String text) {
        if (text == null) return "";
        var matcher = Pattern.compile("\\$\\$|\\$[A-Za-z_][A-Za-z0-9_]*").matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String token = matcher.group();
            String replacement;
            if (token.equals("$$")) replacement = "$";
            else {
                Object value = get(token);
                if (value == UNDEFINED)
                    throw new IllegalArgumentException("Unbound guided input: " + token);
                replacement = stringify(value);
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private @NonNull String stringify(Object value) {
        return value == null ? "" : value.toString();
    }

    /** Binds a tool result's fields to {@code $var}s per the output bindings map. */
    public void bindTool(Map<String, String> outputs, ToolResult result) {
        if (outputs == null || result == null) {
            return;
        }
        JsonNode node = parseContent(result.content());
        for (var entry : outputs.entrySet()) {
            String var = entry.getKey();
            String field = entry.getValue();
            Object value =
                    switch (field) {
                        case "success" -> result.success();
                        case "status" -> result.status().name();
                        case "errorCode" -> result.errorCode();
                        default ->
                                !result.success() && !"content".equals(field)
                                        ? UNDEFINED
                                        : extractField(node, field, result.content());
                    };
            put(var, value == null ? "" : value);
        }
    }

    /** Binds a generate result's fields to {@code $var}s per the output bindings map. */
    public void bindGenerate(Map<String, String> outputs, VetoResponse response) {
        if (outputs == null || response == null) {
            return;
        }
        for (var entry : outputs.entrySet()) {
            String var = entry.getKey();
            String field = entry.getValue();
            String message = response.message();
            Object value =
                    switch (field) {
                        case "thought" -> response.thought();
                        case "message" -> message;
                        default -> message != null ? message : "";
                    };
            put(var, value == null ? "" : value);
        }
    }

    private JsonNode parseContent(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(content);
        } catch (Exception e) {
            return null; // non-JSON content → extractField falls back to the raw string
        }
    }

    private Object extractField(JsonNode node, String field, String rawContent) {
        if (field == null || field.isBlank() || "content".equals(field)) return rawContent;
        if (node == null)
            throw new IllegalArgumentException(
                    "Result is not JSON; bind content instead of " + field);
        JsonNode at = node.get(field);
        if (at == null) {
            throw new IllegalArgumentException("Missing guided result field: " + field);
        }
        if (at.isNumber()) {
            return at.numberValue();
        }
        if (at.isBoolean()) {
            return at.booleanValue();
        }
        if (at.isNull()) return at;
        return objectMapper.convertValue(at, ToolDocs.nonNullClass(Object.class));
    }

    /** Number of bindings in this scope (excludes parent). */
    public int size() {
        return bindings.size();
    }

    /**
     * Synthesizes a result string from accumulated bindings (for STOP without a result binding).
     */
    public @NonNull String synthesize() {
        if (bindings.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (var entry : bindings.entrySet()) {
            if (!sb.isEmpty()) {
                sb.append("\n");
            }
            sb.append(entry.getKey()).append("=").append(stringify(entry.getValue()));
        }
        return sb.toString();
    }

    public @NonNull Optional<Object> opt(@NonNull String var) {
        Object v = get(var);
        return v == UNDEFINED ? Optional.empty() : Optional.of(v);
    }
}
