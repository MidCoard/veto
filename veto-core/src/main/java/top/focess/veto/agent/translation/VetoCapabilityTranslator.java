package top.focess.veto.agent.translation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import top.focess.veto.agent.mcp.ParameterSchema;
import top.focess.veto.agent.mcp.ToolDefinition;
import top.focess.veto.agent.mcp.ToolSchemaCompiler;
import top.focess.veto.llm.core.VetoResponse;

/**
 * Translates the unified capability manifest into provider-facing forms and emits the per-turn
 * {@code veto_pulse} response schema. Implements {@link CapabilityTranslator} (the translator owns
 * it; the {@code PromptCompiler} calls it).
 *
 * <p>Two responsibilities (translator owns both, superseding the old single-{@code call} {@code
 * SchemaNormalizerService}):
 *
 * <ol>
 *   <li>{@link #translateTools} - manifest {@link ToolDefinition} (sealed) -> flat {@link
 *       top.focess.veto.llm.core.ToolDefinition} (name/description/inputSchema) for {@code
 *       VetoRequest.tools}.
 *   <li>{@link #vetoResponseSchema} - the per-turn {@code veto_pulse} schema variant that
 *       constrains the model to a {@link VetoResponse}, governed by the guided state. {@code
 *       thought} is always optional.
 * </ol>
 *
 * <p>The emitted schema is provider-agnostic JSON Schema (Draft 7). Provider-specific strictness
 * adaptation (OpenAI {@code strict} + {@code additionalProperties:false} injection, GBNF grammar
 * compilation) is applied at the provider/client layer (the LLM clients); this translator emits the
 * canonical shape both compile against.
 */
@Service
public class VetoCapabilityTranslator implements CapabilityTranslator {

    private static final @NonNull ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public @NonNull List<top.focess.veto.llm.core.ToolDefinition> translateTools(
            List<ToolDefinition> manifest) {
        List<top.focess.veto.llm.core.ToolDefinition> flat = new ArrayList<>();
        if (manifest == null) return flat;
        for (ToolDefinition def : manifest) {
            Map<String, Object> inputSchema = inputSchemaOf(def);
            flat.add(
                    new top.focess.veto.llm.core.ToolDefinition(
                            def.name(),
                            def.description(),
                            inputSchema,
                            def.examples(),
                            def.documentation(),
                            def.returnExamples(),
                            def.resultFormats()));
        }
        return flat;
    }

    @Override
    public @NonNull JsonNode vetoResponseSchema(boolean guidedSwitch) {
        return vetoResponseSchema(guidedSwitch, List.of());
    }

    @Override
    public @NonNull JsonNode vetoResponseSchema(
            boolean guidedSwitch, @NonNull List<top.focess.veto.llm.core.ToolDefinition> tools) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "object");
        ObjectNode properties = MAPPER.createObjectNode();
        ArrayNode required = MAPPER.createArrayNode();

        // thought is always optional: present as a property, never required, never forbidden.
        properties.set(
                "thought",
                stringNode("Optional internal reasoning before acting. Include when useful."));

        if (!guidedSwitch) {
            ObjectNode calls = MAPPER.createObjectNode();
            calls.put("type", "array");
            calls.set("items", callItemSchema(tools));
            calls.put("minItems", 1);
            calls.put(
                    "description",
                    "The list of parallel tool calls to execute. Mutually exclusive with actions.");
            properties.set("calls", calls);
        }
        // guidedSwitch -> calls absent; additionalProperties:false forbids it.

        properties.set(
                "message",
                stringNode(
                        "User-facing text. Required when stopping (no tool calls and no actions)."));

        ObjectNode features = MAPPER.createObjectNode();
        features.put("type", "object");
        ObjectNode featureProps = MAPPER.createObjectNode();
        featureProps.set(
                "guided",
                typedSchemaNode(
                        "boolean",
                        "Selects guided (true) vs autonomous (false) for the NEXT iteration."));
        features.set("properties", featureProps);
        ArrayNode featureRequired = MAPPER.createArrayNode();
        featureRequired.add("guided");
        features.set("required", featureRequired);
        features.put("additionalProperties", false);
        properties.set("features", features);
        required.add("features");

        if (guidedSwitch) {
            ObjectNode actions = MAPPER.createObjectNode();
            actions.put("type", "array");
            actions.set("items", actionItemSchema(tools));
            actions.put("minItems", 1);
            actions.put(
                    "description",
                    "The guided-mode IR: an ordered list of actions. Present only when"
                            + " features.guided=true. Mutually exclusive with calls.");
            properties.set("actions", actions);
            required.add("actions");
        }

        root.set("properties", properties);
        root.set("required", required);
        root.put("additionalProperties", false);
        return root;
    }

    /** The complete guided IR, including per-tool input-name constraints. */
    private static @NonNull JsonNode actionItemSchema(
            @NonNull List<top.focess.veto.llm.core.ToolDefinition> tools) {
        ArrayNode variants = MAPPER.createArrayNode();
        tools.stream()
                .sorted(
                        java.util.Comparator.comparing(
                                top.focess.veto.llm.core.ToolDefinition::name,
                                String.CASE_INSENSITIVE_ORDER))
                .forEach(tool -> variants.add(toolActionSchema(tool)));
        variants.add(generateActionSchema());
        variants.add(gotoActionSchema());
        variants.add(conditionalGotoActionSchema());
        variants.add(stopActionSchema());
        ObjectNode union = MAPPER.createObjectNode();
        union.set("anyOf", variants);
        return union;
    }

    private static @NonNull ObjectNode toolActionSchema(
            top.focess.veto.llm.core.@NonNull ToolDefinition tool) {
        ObjectNode properties = actionProperties("tool");
        properties.set("tool", enumString(tool.name(), "The catalogued tool to execute."));
        properties.set("inputs", bindingInputsSchema(tool));
        properties.set("outputs", stringMapSchema("Result variable name to result field."));
        return closedObject(properties, "id", "label", "type", "tool", "inputs", "outputs");
    }

    private static @NonNull ObjectNode generateActionSchema() {
        ObjectNode properties = actionProperties("generate");
        properties.set("prompt", stringNode("Prompt for the scoped model generation."));
        properties.set("inputs", stringMapSchema("Input name to literal or $variable reference."));
        properties.set("outputs", stringMapSchema("Result variable name to message or thought."));
        properties.set("thought", typedSchemaNode("boolean", "Whether to request reasoning."));
        properties.set("model_tier", stringNode("Optional model-tier override."));
        properties.set("temperature", typedSchemaNode("number", "Optional temperature override."));
        return closedObject(properties, "id", "label", "type", "prompt", "inputs", "outputs");
    }

    private static @NonNull ObjectNode gotoActionSchema() {
        ObjectNode properties = actionProperties("goto");
        properties.set("index", typedSchemaNode("integer", "Zero-based target action index."));
        return closedObject(properties, "id", "label", "type", "index");
    }

    private static @NonNull ObjectNode conditionalGotoActionSchema() {
        ObjectNode properties = actionProperties("conditional_goto");
        properties.set("check", checkSchema());
        properties.set(
                "true_goto", typedSchemaNode("integer", "Target index when the check passes."));
        properties.set(
                "false_goto", typedSchemaNode("integer", "Optional target index when it fails."));
        return closedObject(properties, "id", "label", "type", "check", "true_goto");
    }

    private static @NonNull ObjectNode stopActionSchema() {
        ObjectNode properties = actionProperties("STOP");
        properties.set(
                "result_binding", stringNode("Optional scope variable returned as the result."));
        return closedObject(properties, "id", "label", "type");
    }

    private static @NonNull ObjectNode actionProperties(@NonNull String type) {
        ObjectNode properties = MAPPER.createObjectNode();
        properties.set("id", stringNode("Unique action id."));
        properties.set("label", stringNode("Short human-readable action label."));
        properties.set("type", enumString(type, "Action discriminator."));
        return properties;
    }

    private static @NonNull JsonNode checkSchema() {
        ArrayNode variants = MAPPER.createArrayNode();
        variants.add(checkVariant("equals", "var", "value"));
        variants.add(checkVariant("not_equals", "var", "value"));
        variants.add(checkVariant("contains", "var", "substring"));
        variants.add(checkVariant("matches", "var", "regex"));
        variants.add(checkVariant("empty", "var"));
        variants.add(checkVariant("not_empty", "var"));
        variants.add(checkVariant("numeric", "var", "op", "value"));
        variants.add(checkVariant("exit_ok", "step_id"));
        variants.add(checkVariant("llm", "prompt", "var"));
        ObjectNode union = MAPPER.createObjectNode();
        union.set("anyOf", variants);
        return union;
    }

    private static @NonNull ObjectNode checkVariant(
            @NonNull String kind, String @NonNull ... fields) {
        ObjectNode properties = MAPPER.createObjectNode();
        properties.set("kind", enumString(kind, "Check discriminator."));
        for (String field : fields) {
            properties.set(field, stringNode("Check operand."));
        }
        String[] required = new String[fields.length + 1];
        required[0] = "kind";
        System.arraycopy(fields, 0, required, 1, fields.length);
        return closedObject(properties, required);
    }

    private static @NonNull ObjectNode bindingInputsSchema(
            top.focess.veto.llm.core.@NonNull ToolDefinition tool) {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = MAPPER.createObjectNode();
        JsonNode toolSchema = MAPPER.valueToTree(tool.inputSchema());
        toolSchema
                .path("properties")
                .fieldNames()
                .forEachRemaining(
                        name ->
                                properties.set(
                                        name,
                                        stringNode(
                                                "Literal value or $variable reference for "
                                                        + name
                                                        + ".")));
        schema.set("properties", properties);
        JsonNode required = toolSchema.path("required");
        if (required.isArray() && !required.isEmpty()) {
            schema.set("required", required.deepCopy());
        }
        schema.put("additionalProperties", false);
        return schema;
    }

    private static @NonNull ObjectNode stringMapSchema(@NonNull String description) {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.put("description", description);
        schema.set("additionalProperties", typedSchemaNode("string", null));
        return schema;
    }

    private static @NonNull ObjectNode closedObject(
            @NonNull ObjectNode properties, String @NonNull ... requiredFields) {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        ArrayNode required = MAPPER.createArrayNode();
        for (String field : requiredFields) {
            required.add(field);
        }
        schema.set("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static @NonNull ObjectNode enumString(
            @NonNull String value, @NonNull String description) {
        ObjectNode schema = stringNode(description);
        ArrayNode allowed = MAPPER.createArrayNode();
        allowed.add(value);
        schema.set("enum", allowed);
        return schema;
    }

    /**
     * Binds every allowed tool name to that tool's exact argument schema. A single name enum beside
     * a generic object is not sufficient: it allows a model to pair any name with any arguments and
     * leaves the most important part of the call contract in prose only.
     */
    private static @NonNull JsonNode callItemSchema(
            @NonNull List<top.focess.veto.llm.core.ToolDefinition> tools) {
        if (tools.isEmpty()) {
            return callVariant(null);
        }

        ArrayNode variants = MAPPER.createArrayNode();
        tools.stream()
                .sorted(
                        java.util.Comparator.comparing(
                                top.focess.veto.llm.core.ToolDefinition::name,
                                String.CASE_INSENSITIVE_ORDER))
                .forEach(tool -> variants.add(callVariant(tool)));
        ObjectNode union = MAPPER.createObjectNode();
        union.set("anyOf", variants);
        return union;
    }

    private static @NonNull ObjectNode callVariant(top.focess.veto.llm.core.ToolDefinition tool) {
        ObjectNode variant = MAPPER.createObjectNode();
        variant.put("type", "object");

        ObjectNode properties = MAPPER.createObjectNode();
        ObjectNode toolName =
                stringNode("The tool name, exactly as listed in this turn's catalog.");
        if (tool != null) {
            ArrayNode onlyName = MAPPER.createArrayNode();
            onlyName.add(tool.name());
            toolName.set("enum", onlyName);
        }
        properties.set("tool_name", toolName);
        properties.set("args", tool == null ? typedSchemaNode("object", null) : argsSchema(tool));
        variant.set("properties", properties);

        ArrayNode required = MAPPER.createArrayNode();
        required.add("tool_name");
        required.add("args");
        variant.set("required", required);
        variant.put("additionalProperties", false);
        return variant;
    }

    private static @NonNull JsonNode argsSchema(
            top.focess.veto.llm.core.@NonNull ToolDefinition tool) {
        JsonNode converted = MAPPER.valueToTree(tool.inputSchema());
        if (!converted.isObject()) {
            return typedSchemaNode("object", null);
        }
        ObjectNode schema = (ObjectNode) converted;
        if (!schema.has("type")) {
            schema.put("type", "object");
        }
        closeDeclaredObjects(schema);
        return schema;
    }

    /** Rejects invented object fields while preserving an explicit remote-tool policy. */
    private static void closeDeclaredObjects(@NonNull JsonNode schema) {
        if (schema.isObject()) {
            ObjectNode object = (ObjectNode) schema;
            if ("object".equals(object.path("type").asText())
                    && object.has("properties")
                    && !object.has("additionalProperties")) {
                object.put("additionalProperties", false);
            }
            object.elements().forEachRemaining(VetoCapabilityTranslator::closeDeclaredObjects);
        } else if (schema.isArray()) {
            schema.elements().forEachRemaining(VetoCapabilityTranslator::closeDeclaredObjects);
        }
    }

    /** Resolves a manifest tool's inputSchema to a flat {@code Map} for the provider tool list. */
    private @NonNull Map<String, Object> inputSchemaOf(@NonNull ToolDefinition def) {
        ParameterSchema params = def.parameters();
        JsonNode schema =
                switch (params) {
                    case ParameterSchema.Structured s ->
                            s.argsClass().isRecord()
                                    ? ToolSchemaCompiler.compileFromRecord(s.argsClass())
                                    : emptyObjectSchema();
                    case ParameterSchema.Raw r -> r.jsonSchema();
                };
        // AgentToolDefinition has no inputSchema field; derive from its Structured argsClass above.
        return MAPPER.convertValue(schema, new TypeReference<Map<String, Object>>() {});
    }

    private static @NonNull ObjectNode emptyObjectSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", MAPPER.createObjectNode());
        schema.put("additionalProperties", false);
        return schema;
    }

    private static @NonNull ObjectNode stringNode(@NonNull String description) {
        return typedSchemaNode("string", description);
    }

    /** Builds a typed schema node (boolean/object/...) with an optional description. */
    private static @NonNull ObjectNode typedSchemaNode(@NonNull String type, String description) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", type);
        if (description != null) {
            node.put("description", description);
        }
        return node;
    }
}
