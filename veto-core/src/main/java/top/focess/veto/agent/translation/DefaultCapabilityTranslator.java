package top.focess.veto.agent.translation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.mcp.AgentToolDefinition;
import top.focess.veto.agent.mcp.NativeToolDefinition;
import top.focess.veto.agent.mcp.RemoteToolDefinition;
import top.focess.veto.agent.mcp.ToolDefinition;

/**
 * The default {@link CapabilityTranslator} - the deterministic {@code veto_pulse} schema builder +
 * flat-tool translator. Registered as a {@code @ConditionalOnMissingBean} so a richer
 * implementation (provider-specific schema nuances, native-tool reflection via {@code
 * ToolSchemaCompiler}) overrides it when present.
 *
 * <p>The {@code veto_pulse} response schema is fully specified; building it here is transcription.
 * The flat-tool translation is best-effort for native/agent tools (their JSON Schema is compiled
 * from the args class); remote tools carry their raw input schema.
 *
 * <p><b>Temporary standalone-test stub.</b> Exists only so this worktree compiles + tests in
 * isolation without a richer implementation. A richer {@code CapabilityTranslator} impl wins when
 * present and this class is removed.
 */
public class DefaultCapabilityTranslator implements CapabilityTranslator {

    private final @NonNull ObjectMapper objectMapper;

    public DefaultCapabilityTranslator(@NonNull ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public @NonNull JsonNode vetoResponseSchema(boolean guidedSwitch) {
        Map<String, Object> properties = new LinkedHashMap<>();

        // thought is always optional: present as a property, never required, never forbidden.
        properties.put(
                "thought",
                orderedMap(
                        "type", "string",
                        "description",
                                "Optional internal reasoning before acting. Include when useful."));

        if (!guidedSwitch) {
            // autonomous: calls allowed (optional), actions forbidden (absent).
            properties.put(
                    "calls",
                    orderedMap(
                            "type",
                            "array",
                            "description",
                            "The parallel tool calls to execute. Empty if none.",
                            "items",
                            orderedMap(
                                    "type",
                                    "object",
                                    "properties",
                                    orderedMap(
                                            "tool_name", orderedMap("type", "string"),
                                            "args", orderedMap("type", "object")),
                                    "required",
                                    List.of("tool_name", "args"),
                                    "additionalProperties",
                                    false)));
        } else {
            // guided-switch: actions required, calls forbidden (absent).
            properties.put(
                    "actions",
                    orderedMap(
                            "type", "array",
                            "description",
                                    "The typed actions program (IR) to drive in guided mode.",
                            "items", orderedMap("type", "object")));
        }
        properties.put(
                "message",
                orderedMap(
                        "type", "string",
                        "description",
                                "User-facing text. Required when stopping (no tool calls and no actions)."));
        properties.put(
                "features",
                orderedMap(
                        "type",
                        "object",
                        "description",
                        "Describes the NEXT iteration's status.",
                        "properties",
                        orderedMap("guided", orderedMap("type", "boolean")),
                        "required",
                        List.of("guided"),
                        "additionalProperties",
                        false));

        List<String> required = new ArrayList<>();
        required.add("features");
        if (guidedSwitch) {
            required.add("actions");
        }

        Map<String, Object> schema =
                orderedMap(
                        "type",
                        "object",
                        "properties",
                        properties,
                        "required",
                        required,
                        "additionalProperties",
                        false);
        return objectMapper.valueToTree(schema);
    }

    @Override
    public @NonNull List<top.focess.veto.llm.core.ToolDefinition> translateTools(
            List<ToolDefinition> manifest) {
        List<top.focess.veto.llm.core.ToolDefinition> flat = new ArrayList<>();
        if (manifest == null) {
            return flat;
        }
        for (ToolDefinition def : manifest) {
            flat.add(
                    switch (def) {
                        case NativeToolDefinition n ->
                                new top.focess.veto.llm.core.ToolDefinition(
                                        n.name(), n.description(), minimalObjectSchema());
                        case RemoteToolDefinition r ->
                                new top.focess.veto.llm.core.ToolDefinition(
                                        r.name(), r.description(), jsonNodeToMap(r.inputSchema()));
                        case AgentToolDefinition a ->
                                new top.focess.veto.llm.core.ToolDefinition(
                                        a.name(), a.description(), minimalObjectSchema());
                    });
        }
        return flat;
    }

    private @NonNull Map<String, Object> minimalObjectSchema() {
        return orderedMap("type", "object", "properties", Map.of(), "additionalProperties", false);
    }

    @SuppressWarnings("unchecked")
    private @NonNull Map<String, Object> jsonNodeToMap(JsonNode node) {
        if (node == null || node.isNull()) {
            return minimalObjectSchema();
        }
        return objectMapper.convertValue(node, Map.class);
    }

    private static @NonNull Map<String, Object> orderedMap(Object @NonNull ... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** Convenience: the schema as an ObjectNode (for tests). */
    public @NonNull ObjectNode schemaAsObject(boolean guidedSwitch) {
        return (ObjectNode) vetoResponseSchema(guidedSwitch);
    }
}
