package top.focess.veto.agent.translation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import top.focess.veto.agent.mcp.AgentToolDefinition;
import top.focess.veto.agent.mcp.NativeToolDefinition;
import top.focess.veto.agent.mcp.RemoteToolDefinition;
import top.focess.veto.agent.mcp.ToolDefinition;

/**
 * The default {@link CapabilityTranslator} — the deterministic, -specified {@code veto_pulse}
 * schema builder + flat-tool translator. Registered as a {@code @ConditionalOnMissingBean} so Part
 * 5's richer implementation (provider-specific schema nuances, native-tool reflection via {@code
 * ToolSchemaCompiler}) overrides it when present.
 *
 * <p>The {@code veto_pulse} response schema is fully specified in — building it here is
 * transcription, not a Part-5 judgment. The flat-tool translation is best-effort for native/agent
 * tools (Part 5 compiles their JSON Schema from the args class); remote tools carry their raw input
 * schema.
 *
 * <p><b>Temporary standalone-test stub.</b> Exists only so this worktree compiles + tests in
 * isolation without Part 5. Part 5's real {@code CapabilityTranslator} impl wins at Phase-2 merge
 * and this class is removed.
 */
public class DefaultCapabilityTranslator implements CapabilityTranslator {

    private final ObjectMapper objectMapper;

    public DefaultCapabilityTranslator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public JsonNode vetoResponseSchema(boolean thoughtRequired, boolean guidedSwitch) {
        Map<String, Object> properties = new LinkedHashMap<>();

        if (thoughtRequired) {
            properties.put(
                    "thought",
                    orderedMap(
                            "type", "string",
                            "description",
                                    "Deep step-by-step reasoning explaining the plan and actions."));
        }
        if (!guidedSwitch) {
            // autonomous: calls allowed (optional), actionsProgram forbidden (absent).
            properties.put(
                    "calls",
                    orderedMap(
                            "type", "array",
                            "description", "The parallel tool calls to execute. Empty if none.",
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
            // guided-switch: actionsProgram required, calls forbidden (absent).
            properties.put(
                    "actionsProgram",
                    orderedMap(
                            "type", "object",
                            "description",
                                    "The typed actions program (IR) to drive in guided mode."));
        }
        properties.put(
                "message",
                orderedMap(
                        "type", "string",
                        "description",
                                "User-facing text. Required when thought is OFF or when is_finished."));
        properties.put("is_finished", orderedMap("type", "boolean"));
        properties.put(
                "features",
                orderedMap(
                        "type",
                        "object",
                        "description",
                        "Describes the NEXT iteration's status.",
                        "properties",
                        orderedMap(
                                "guided", orderedMap("type", "boolean"),
                                "thought", orderedMap("type", "boolean")),
                        "required",
                        List.of("guided", "thought"),
                        "additionalProperties",
                        false));

        List<String> required = new ArrayList<>();
        required.add("is_finished");
        required.add("features");
        if (thoughtRequired) {
            required.add("thought");
        }
        if (!thoughtRequired) {
            required.add("message");
        }
        if (guidedSwitch) {
            required.add("actionsProgram");
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
    public List<top.focess.veto.llm.core.ToolDefinition> translateTools(
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

    private Map<String, Object> minimalObjectSchema() {
        return orderedMap("type", "object", "properties", Map.of(), "additionalProperties", false);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonNodeToMap(JsonNode node) {
        if (node == null || node.isNull()) {
            return minimalObjectSchema();
        }
        return objectMapper.convertValue(node, Map.class);
    }

    private static Map<String, Object> orderedMap(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** Convenience: the schema as an ObjectNode (for tests). */
    public ObjectNode schemaAsObject(boolean thoughtRequired, boolean guidedSwitch) {
        return (ObjectNode) vetoResponseSchema(thoughtRequired, guidedSwitch);
    }
}
