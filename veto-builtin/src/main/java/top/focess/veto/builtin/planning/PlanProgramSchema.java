package top.focess.veto.builtin.planning;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.ContextualInputSchemaSource;
import top.focess.veto.api.agent.tool.ResponseSubmission;
import top.focess.veto.api.agent.tool.ToolSchemaReferences;

/**
 * Tool-owned plan language schema; executable tool names and inputs are checked against the live
 * catalog.
 */
public final class PlanProgramSchema implements ContextualInputSchemaSource {
    public static final @NonNull List<String> MODEL_TIERS = List.of("TOP", "MID", "LOW", "LOCAL");
    private static final @NonNull ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public @NonNull JsonNode schema(@NonNull Context context) {
        return create(
                context.tools().stream()
                        .filter(t -> !context.submissions().containsKey(t.name()))
                        .toList(),
                context.localTools(),
                context.submissions().containsValue(ResponseSubmission.Kind.ANSWER));
    }

    @Override
    public @NonNull JsonNode schema() {
        return create(List.of(), Set.of(), true, true);
    }

    public static @NonNull JsonNode create(
            @NonNull List<top.focess.veto.api.llm.ToolDefinition> tools) {
        return create(tools, Set.of(), false, false);
    }

    /** Java record tools preserve their optional-null convention; remote schemas remain exact. */
    public static @NonNull JsonNode create(
            @NonNull List<top.focess.veto.api.llm.ToolDefinition> tools,
            @NonNull Set<String> javaRecordTools) {
        return create(tools, javaRecordTools, false, false);
    }

    /** Expose cited generation only when this invocation has an answer-submission capability. */
    public static @NonNull JsonNode create(
            @NonNull List<top.focess.veto.api.llm.ToolDefinition> tools,
            @NonNull Set<String> javaRecordTools,
            boolean citationsAvailable) {
        return create(tools, javaRecordTools, false, citationsAvailable);
    }

    private static @NonNull JsonNode create(
            @NonNull List<top.focess.veto.api.llm.ToolDefinition> tools,
            @NonNull Set<String> javaRecordTools,
            boolean genericTools,
            boolean citationsAvailable) {
        var root =
                MAPPER.createObjectNode().put("type", "object").put("additionalProperties", false);
        var actions = root.putObject("properties").putObject("actions");
        actions.put("type", "array")
                .put("minItems", 1)
                .put(
                        "description",
                        "Ordered program actions. Each has id, label, type. End with STOP. See the action variants and tool examples.");
        actions.set(
                "items",
                actionItemSchema(tools, javaRecordTools, genericTools, citationsAvailable));
        root.putArray("required").add("actions");
        return root;
    }

    /** The complete plan IR, including per-tool input-name constraints. */
    private static @NonNull JsonNode actionItemSchema(
            @NonNull List<top.focess.veto.api.llm.ToolDefinition> tools,
            @NonNull Set<String> javaRecordTools,
            boolean genericTools,
            boolean citationsAvailable) {
        ArrayNode variants = MAPPER.createArrayNode();
        tools.stream()
                .sorted(
                        Comparator.comparing(
                                top.focess.veto.api.llm.ToolDefinition::name,
                                String.CASE_INSENSITIVE_ORDER))
                .forEach(
                        tool ->
                                variants.add(
                                        toolActionSchema(
                                                tool, javaRecordTools.contains(tool.name()))));
        if (genericTools) {
            var properties = actionProperties("tool");
            properties.set("tool", stringNode("Exact name from the current tool catalog."));
            properties.set(
                    "inputs",
                    MAPPER.createObjectNode()
                            .put("type", "object")
                            .put(
                                    "description",
                                    "Arguments matching the selected tool; values may reference $variables."));
            properties.set(
                    "outputs",
                    stringMapSchema(
                            "New variable name to tool result field, e.g. text to content."));
            variants.add(
                    closedObject(properties, "id", "label", "type", "tool", "inputs", "outputs"));
        }
        variants.add(generateActionSchema(citationsAvailable));
        variants.add(gotoActionSchema());
        variants.add(conditionalGotoActionSchema());
        variants.add(stopActionSchema());
        ObjectNode union = MAPPER.createObjectNode();
        union.set("anyOf", variants);
        return union;
    }

    private static @NonNull ObjectNode toolActionSchema(
            top.focess.veto.api.llm.@NonNull ToolDefinition tool, boolean javaNulls) {
        ObjectNode properties = actionProperties("tool");
        properties.set("tool", enumString(tool.name(), "The catalogued tool to execute."));
        properties.set("inputs", bindingInputsSchema(tool, javaNulls));
        properties.set("outputs", stringMapSchema("Result variable name to result field."));
        return closedObject(properties, "id", "label", "type", "tool", "inputs", "outputs");
    }

    private static @NonNull ObjectNode generateActionSchema(boolean citationsAvailable) {
        ObjectNode properties = actionProperties("generate");
        properties.set("prompt", stringNode("Prompt for the scoped model generation."));
        properties.set(
                "inputs",
                MAPPER.createObjectNode()
                        .put("type", "object")
                        .put(
                                "description",
                                "Optional named inputs supplied to the generation as data. Values may reference $variables. Prompt placeholders are optional; omitted inputs means no inputs."));
        var responseMode =
                stringNode(
                        "Output channel: TEXT (default) returns the requested content without tool calls."
                                + (citationsAvailable
                                        ? " CITATIONS requires an answer with clickable conversation-source references; select it when the requested output needs those links."
                                        : ""));
        var modes = responseMode.putArray("enum").add("TEXT");
        if (citationsAvailable) modes.add("CITATIONS");
        responseMode.put("default", "TEXT");
        properties.set("response_mode", responseMode);
        var outputs =
                stringMapSchema(
                        "New variable name to the generated text field. Example: answer maps to message.");
        var field = MAPPER.createObjectNode().put("type", "string");
        field.putArray("enum").add("message");
        outputs.set("additionalProperties", field);
        properties.set("outputs", outputs);
        var tiers = stringNode("Optional model-tier override.");
        var names = tiers.putArray("enum");
        for (String tier : MODEL_TIERS) names.add(tier);
        properties.set("model_tier", tiers);
        properties.set(
                "temperature",
                typedSchemaNode("number", "Optional temperature override.")
                        .put("minimum", 0)
                        .put("maximum", 2));
        return closedObject(properties, "id", "label", "type", "prompt", "outputs");
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
        properties.set("id", stringNode("Unique action id.").put("minLength", 1));
        properties.set(
                "label", stringNode("Short human-readable action label.").put("minLength", 1));
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
        var numeric = checkVariant("numeric", "var", "op", "value");
        ((ObjectNode) numeric.path("properties").path("op"))
                .putArray("enum")
                .add("gt")
                .add("lt")
                .add("eq")
                .add("gte")
                .add("lte");
        variants.add(numeric);
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

    private static @NonNull JsonNode bindingInputsSchema(
            top.focess.veto.api.llm.@NonNull ToolDefinition tool, boolean javaNulls) {
        JsonNode toolSchema = MAPPER.valueToTree(tool.inputSchema());
        // Preserve open dictionaries, unions and required fields from remote tools. The inputs
        // object itself is not a binding expression: ToolAction stores a map of input values.
        return bindingLiteralSchema(ToolSchemaReferences.inlineForEmbedding(toolSchema), javaNulls);
    }

    private static boolean containsName(@NonNull JsonNode names, @NonNull String name) {
        for (var value : names) if (value.asText().equals(name)) return true;
        return false;
    }

    private static @NonNull JsonNode bindingValueSchema(
            @NonNull JsonNode original, boolean javaNulls) {
        return bindingValueSchema(original, false, javaNulls);
    }

    private static @NonNull JsonNode bindingValueSchema(
            @NonNull JsonNode original, boolean nullable, boolean javaNulls) {
        if (!original.isObject()) return original.deepCopy();
        JsonNode literal = bindingLiteralSchema(original, javaNulls);
        ObjectNode reference =
                MAPPER.createObjectNode()
                        .put("type", "string")
                        .put("pattern", "^\\$[A-Za-z_][A-Za-z0-9_]*$");
        ObjectNode union = MAPPER.createObjectNode();
        var alternatives = union.putArray("anyOf").add(literal).add(reference);
        // $$ escapes are an expression form; Gateway validates the decoded literal before
        // acceptance.
        if (permitsString(original))
            alternatives.add(
                    MAPPER.createObjectNode()
                            .put("type", "string")
                            .put("pattern", "^\\$\\$")
                            .put(
                                    "description",
                                    "Escaped literal: $$ becomes $. Its decoded value must satisfy this parameter."));
        if (nullable) alternatives.add(MAPPER.createObjectNode().put("type", "null"));
        return union;
    }

    /** Transform value-bearing schema positions; assertions on property names remain literal. */
    private static @NonNull JsonNode bindingLiteralSchema(
            @NonNull JsonNode original, boolean javaNulls) {
        if (!original.isObject()) return original.deepCopy();
        ObjectNode literal = original.deepCopy();
        for (String keyword : List.of("properties", "patternProperties")) {
            if (!original.path(keyword).isObject()) continue;
            ObjectNode properties = MAPPER.createObjectNode();
            original.path(keyword)
                    .properties()
                    .forEach(
                            entry ->
                                    properties.set(
                                            entry.getKey(),
                                            bindingValueSchema(
                                                    entry.getValue(),
                                                    javaNulls
                                                            && keyword.equals("properties")
                                                            && !containsName(
                                                                    original.path("required"),
                                                                    entry.getKey()),
                                                    javaNulls)));
            literal.set(keyword, properties);
        }
        for (String keyword :
                List.of(
                        "items",
                        "additionalProperties",
                        "contains",
                        "additionalItems",
                        "unevaluatedProperties",
                        "unevaluatedItems")) {
            if (original.path(keyword).isObject() || original.path(keyword).isBoolean())
                literal.set(keyword, bindingValueSchema(original.path(keyword), javaNulls));
            else if (keyword.equals("items") && original.path(keyword).isArray()) {
                var items = MAPPER.createArrayNode();
                for (var item : original.path(keyword))
                    items.add(bindingValueSchema(item, javaNulls));
                literal.set(keyword, items);
            }
        }
        for (String keyword : List.of("anyOf", "oneOf", "allOf")) {
            if (!original.path(keyword).isArray()) continue;
            var choices = MAPPER.createArrayNode();
            for (var option : original.path(keyword))
                choices.add(bindingLiteralSchema(option, javaNulls));
            literal.set(keyword, choices);
        }
        if (original.path("prefixItems").isArray()) {
            var items = MAPPER.createArrayNode();
            for (var item : original.path("prefixItems"))
                items.add(bindingValueSchema(item, javaNulls));
            literal.set("prefixItems", items);
        }
        return literal;
    }

    private static boolean permitsString(@NonNull JsonNode schema) {
        var type = schema.path("type");
        if (type.asText().equals("string") || type.isMissingNode()) return true;
        for (var candidate : type) if (candidate.asText().equals("string")) return true;
        for (var alternative : schema.path("anyOf")) if (permitsString(alternative)) return true;
        return false;
    }

    private static @NonNull ObjectNode stringMapSchema(@NonNull String description) {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.put("description", description);
        schema.set(
                "propertyNames",
                MAPPER.createObjectNode()
                        .put("pattern", "^(?!CURRENT_STEPS$)[A-Za-z_][A-Za-z0-9_]*$"));
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
