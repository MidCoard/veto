package top.focess.veto.agent.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.veto.agent.mcp.ToolResult;
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
    public static final Object UNDEFINED = new Object();

    private final Map<String, Object> bindings = new HashMap<>();
    private final @NonNull Scope parent;
    private final @NonNull ObjectMapper objectMapper;

    public
    @NonNull
    Scope(@NonNull ObjectMapper objectMapper) {
        this(objectMapper, null);
    }

    public
    @NonNull
    Scope(@NonNull ObjectMapper objectMapper, @NonNull Scope parent) {
        this.objectMapper = objectMapper;
        this.parent = parent;
    }

    /** Read-through to parent. Missing key returns {@link #UNDEFINED}, not an error. */
    public @NonNull Object get(@NonNull String var) {
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

    public boolean contains(@NonNull String var) {
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
    public @NonNull Object resolveValue(@NonNull String spec) {
        if (spec == null) {
            return UNDEFINED;
        }
        if (spec.startsWith("$")) {
            Object v = get(spec);
            return v == UNDEFINED ? UNDEFINED : v;
        }
        return spec; // literal
    }

    /** Resolves {@code $var} references inside a text against the scope (stringified). */
    public @NonNull String resolveVars(@NonNull String text) {
        if (text == null) {
            return "";
        }
        String out = text;
        for (var entry : bindings.entrySet()) {
            out = out.replace("$" + entry.getKey(), stringify(entry.getValue()));
        }
        return out;
    }

    private @NonNull String stringify(@Nullable Object value) {
        return value == null ? "" : value.toString();
    }

    /** Binds a tool result's fields to {@code $var}s per the output bindings map. */
    public void bindTool(@NonNull Map<String, String> outputs, @NonNull ToolResult result) {
        if (outputs == null || result == null) {
            return;
        }
        JsonNode node = parseContent(result.content());
        for (var entry : outputs.entrySet()) {
            String var = entry.getKey();
            String field = entry.getValue();
            Object value = extractField(node, field, result.content());
            put(var, value);
        }
    }

    /** Binds a generate result's fields to {@code $var}s per the output bindings map. */
    public void bindGenerate(@NonNull Map<String, String> outputs, @NonNull VetoResponse response) {
        if (outputs == null || response == null) {
            return;
        }
        for (var entry : outputs.entrySet()) {
            String var = entry.getKey();
            String field = entry.getValue();
            Object value =
                    switch (field) {
                        case "thought" -> response.thought();
                        case "message" -> response.message();
                        default -> response.message() != null ? response.message() : "";
                    };
            put(var, value);
        }
    }

    private @Nullable JsonNode parseContent(@Nullable String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(content);
        } catch (Exception e) {
            return null; // non-JSON content → extractField falls back to the raw string
        }
    }

    private @Nullable Object extractField(
            @Nullable JsonNode node, @Nullable String field, @Nullable String rawContent) {
        if (node == null || field == null || field.isBlank() || "content".equals(field)) {
            return rawContent;
        }
        JsonNode at = node.get(field);
        if (at == null) {
            return rawContent;
        }
        if (at.isNumber()) {
            return at.numberValue();
        }
        if (at.isBoolean()) {
            return at.booleanValue();
        }
        return at.asText();
    }

    /** Number of bindings in this scope (excludes parent). */
    public int size() {
        return bindings.size();
    }

    /**
     * Synthesizes a result string from accumulated bindings (for STOP without a result binding).
     */
    public String synthesize() {
        if (bindings.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (var entry : bindings.entrySet()) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(entry.getKey()).append("=").append(stringify(entry.getValue()));
        }
        return sb.toString();
    }

    public @Nullable Optional<Object> opt(@NonNull String var) {
        Object v = get(var);
        return v == UNDEFINED ? Optional.empty() : Optional.of(v);
    }
}
