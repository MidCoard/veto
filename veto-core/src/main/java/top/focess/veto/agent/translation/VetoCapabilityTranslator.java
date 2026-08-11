package top.focess.veto.agent.translation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
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

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public @NonNull List<top.focess.veto.llm.core.ToolDefinition> translateTools(
            @Nullable List<ToolDefinition> manifest) {
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
                            def.longDescription(),
                            def.returnExamples()));
        }
        return flat;
    }

    @Override
    public @NonNull JsonNode vetoResponseSchema(boolean guidedSwitch) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "object");
        ObjectNode properties = MAPPER.createObjectNode();
        ArrayNode required = MAPPER.createArrayNode();

        // thought is always optional: present as a property, never required, never forbidden.
        properties.set(
                "thought",
                stringNode("Optional internal reasoning before acting. Include when useful."));

        if (!guidedSwitch) {
            ObjectNode callItem = MAPPER.createObjectNode();
            callItem.put("type", "object");
            ObjectNode itemProps = MAPPER.createObjectNode();
            itemProps.set(
                    "tool_name", stringNode("The tool name, exactly as listed in the manifest."));
            itemProps.set(
                    "args",
                    typedSchemaNode(
                            "object",
                            "The tool arguments as a JSON object matching the tool's input schema."));
            callItem.set("properties", itemProps);
            ArrayNode itemRequired = MAPPER.createArrayNode();
            itemRequired.add("tool_name");
            itemRequired.add("args");
            callItem.set("required", itemRequired);
            ObjectNode calls = MAPPER.createObjectNode();
            calls.put("type", "array");
            calls.set("items", callItem);
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
            actions.set("items", typedSchemaNode("object", null));
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

    /** Resolves a manifest tool's inputSchema to a flat {@code Map} for the provider tool list. */
    private @NonNull Map<String, Object> inputSchemaOf(@NonNull ToolDefinition def) {
        ParameterSchema params = def.parameters();
        JsonNode schema =
                switch (params) {
                    case ParameterSchema.Structured s ->
                            ToolSchemaCompiler.compileFromRecord(s.argsClass());
                    case ParameterSchema.Raw r -> r.jsonSchema();
                };
        // AgentToolDefinition has no inputSchema field; derive from its Structured argsClass above.
        return MAPPER.convertValue(schema, Map.class);
    }

    private static @NonNull ObjectNode stringNode(@NonNull String description) {
        return typedSchemaNode("string", description);
    }

    /** Builds a typed schema node (boolean/object/...) with an optional description. */
    private static @NonNull ObjectNode typedSchemaNode(
            @NonNull String type, @Nullable String description) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", type);
        if (description != null) {
            node.put("description", description);
        }
        return node;
    }
}
