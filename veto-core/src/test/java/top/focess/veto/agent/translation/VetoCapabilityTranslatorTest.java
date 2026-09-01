package top.focess.veto.agent.translation;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.mcp.AgentToolDefinition;
import top.focess.veto.agent.mcp.NativeToolDefinition;
import top.focess.veto.agent.mcp.ParamCategory;
import top.focess.veto.agent.mcp.RiskCategory;
import top.focess.veto.agent.mcp.ToolDocs;
import top.focess.veto.agent.mcp.ToolDocumentation;
import top.focess.veto.agent.mcp.ToolResultFormat;
import top.focess.veto.agent.mcp.tools.LoadSkillTool;

/**
 * Validates {@link VetoCapabilityTranslator} against the per-turn veto_pulse variant matrix and the
 * manifest to flat tool translation.
 */
class VetoCapabilityTranslatorTest {

    private final @NonNull VetoCapabilityTranslator translator = new VetoCapabilityTranslator();

    @Test
    void autonomousHasOptionalThoughtCallsNoActions() {
        JsonNode schema = translator.vetoResponseSchema(false);
        JsonNode props = schema.get("properties");
        JsonNode required = schema.get("required");
        assertTrue(props.has("thought"), "thought always present (optional)");
        assertTrue(props.has("calls"), "calls present when autonomous");
        assertFalse(props.has("actions"), "actions absent when not guided");
        assertFalse(contains(required, "thought"), "thought never required");
        assertFalse(
                contains(required, "message"),
                "message not schema-required (enforcer handles stop)");
        assertTrue(contains(required, "features"));
    }

    @Test
    void guidedSwitchRequiresActionsForbidsCalls() {
        JsonNode schema = translator.vetoResponseSchema(true);
        JsonNode props = schema.get("properties");
        JsonNode required = schema.get("required");
        assertFalse(props.has("calls"), "calls forbidden when guided");
        assertTrue(props.has("actions"), "actions present when guided");
        assertEquals("array", props.get("actions").get("type").asText(), "actions is a flat array");
        assertTrue(contains(required, "actions"), "actions required when guided");
        assertTrue(contains(required, "features"));
        assertFalse(contains(required, "thought"), "thought never required");
        JsonNode actionVariants = props.path("actions").path("items").path("anyOf");
        assertEquals(4, actionVariants.size(), "four non-tool guided action kinds without tools");
        assertTrue(hasDiscriminator(actionVariants, "generate"));
        assertTrue(hasDiscriminator(actionVariants, "goto"));
        assertTrue(hasDiscriminator(actionVariants, "conditional_goto"));
        assertTrue(hasDiscriminator(actionVariants, "STOP"));
    }

    @Test
    void featuresAlwaysRequiredClosedAndGuidedOnly() {
        for (boolean guided : new boolean[] {true, false}) {
            JsonNode schema = translator.vetoResponseSchema(guided);
            JsonNode features = schema.get("properties").get("features");
            assertEquals(false, features.get("additionalProperties").asBoolean());
            assertTrue(contains(features.get("required"), "guided"));
            assertFalse(contains(features.get("required"), "thought"), "features.thought removed");
            assertFalse(features.get("properties").has("thought"), "features.thought removed");
        }
    }

    @Test
    void autonomousCallVariantsBindEachToolNameToItsOwnArgsSchema() {
        Map<String, Object> viewArgs =
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of("absolutePath", Map.of("type", "string")),
                        "required",
                        List.of("absolutePath"));
        Map<String, Object> thinkArgs =
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of("thought", Map.of("type", "string")),
                        "required",
                        List.of("thought"));
        List<top.focess.veto.llm.core.ToolDefinition> tools =
                List.of(
                        new top.focess.veto.llm.core.ToolDefinition(
                                "view_file",
                                "Read a file.",
                                viewArgs,
                                List.of(),
                                ToolDocumentation.empty(),
                                List.of(),
                                List.of(ToolResultFormat.PLAINTEXT)),
                        new top.focess.veto.llm.core.ToolDefinition(
                                "think",
                                "Continue deliberately.",
                                thinkArgs,
                                List.of(),
                                ToolDocumentation.empty(),
                                List.of(),
                                List.of(ToolResultFormat.PLAINTEXT)));

        JsonNode schema = translator.vetoResponseSchema(false, tools);
        JsonNode variants = schema.path("properties").path("calls").path("items").path("anyOf");
        assertEquals(2, variants.size());

        JsonNode think = variantFor(variants, "think");
        JsonNode viewFile = variantFor(variants, "view_file");
        assertEquals(
                "string",
                think.path("properties")
                        .path("args")
                        .path("properties")
                        .path("thought")
                        .path("type")
                        .asText());
        assertFalse(think.path("properties").path("args").path("properties").has("absolutePath"));
        assertEquals(
                "string",
                viewFile.path("properties")
                        .path("args")
                        .path("properties")
                        .path("absolutePath")
                        .path("type")
                        .asText());
        assertFalse(viewFile.path("properties").path("args").path("properties").has("thought"));
        assertFalse(viewFile.path("additionalProperties").asBoolean());
        assertFalse(
                viewFile.path("properties").path("args").path("additionalProperties").asBoolean());
        assertEquals(1, schema.path("properties").path("calls").path("minItems").asInt());
    }

    @Test
    void translateToolsFlattensManifestToNameDescriptionSchema() {
        NativeToolDefinition nativeDef =
                new NativeToolDefinition(
                        "view_file",
                        "Read a file.",
                        RiskCategory.READ_ONLY,
                        false,
                        ToolDocs.nonNullClass(LoadSkillTool.Args.class),
                        Map.<String, ParamCategory>of());
        AgentToolDefinition agent =
                new AgentToolDefinition(
                        "load_skill",
                        "Load a skill.",
                        ToolDocs.nonNullClass(LoadSkillTool.Args.class),
                        Map.<String, ParamCategory>of());
        List<top.focess.veto.llm.core.ToolDefinition> flat =
                translator.translateTools(List.of(nativeDef, agent));
        assertEquals(2, flat.size());
        assertEquals("view_file", flat.get(0).name());
        assertEquals("Read a file.", flat.get(0).description());
        assertNotNull(flat.get(0).inputSchema());
        assertEquals("object", flat.get(0).inputSchema().get("type"));
        assertFalse(
                flat.get(0).examples().isEmpty(),
                "native tool @ToolDoc examples flow through translateTools");
        assertFalse(
                flat.get(1).examples().isEmpty(),
                "agent tool @ToolDoc examples flow through translateTools");
        assertFalse(
                flat.get(0).documentation().behavior().isEmpty(),
                "native tool @ToolDoc documentation flows through translateTools");
        assertFalse(
                flat.get(1).documentation().behavior().isEmpty(),
                "agent tool @ToolDoc documentation flows through translateTools");
    }

    private static boolean contains(JsonNode array, @NonNull String value) {
        if (array == null || !array.isArray()) return false;
        for (JsonNode n : array) {
            if (value.equals(n.asText())) return true;
        }
        return false;
    }

    @Test
    void guidedToolActionsBindToolNameAndInputNames() {
        Map<String, Object> args =
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of("absolutePath", Map.of("type", "string")),
                        "required",
                        List.of("absolutePath"));
        var tool =
                new top.focess.veto.llm.core.ToolDefinition(
                        "view_file",
                        "Read a file.",
                        args,
                        List.of(),
                        ToolDocumentation.empty(),
                        List.of(),
                        List.of(ToolResultFormat.PLAINTEXT));

        JsonNode variants =
                translator
                        .vetoResponseSchema(true, List.of(tool))
                        .path("properties")
                        .path("actions")
                        .path("items")
                        .path("anyOf");
        JsonNode action = actionVariantForTool(variants, "view_file");

        assertEquals(
                "string",
                action.path("properties")
                        .path("inputs")
                        .path("properties")
                        .path("absolutePath")
                        .path("type")
                        .asText());
        assertTrue(
                contains(
                        action.path("properties").path("inputs").path("required"), "absolutePath"));
        assertFalse(
                action.path("properties").path("inputs").path("additionalProperties").asBoolean());
    }

    private static @NonNull JsonNode variantFor(
            @NonNull JsonNode variants, @NonNull String toolName) {
        for (JsonNode variant : variants) {
            if (contains(variant.path("properties").path("tool_name").path("enum"), toolName)) {
                return variant;
            }
        }
        fail("missing call variant for " + toolName);
        throw new AssertionError("unreachable");
    }

    private static boolean hasDiscriminator(
            @NonNull JsonNode variants, @NonNull String discriminator) {
        for (JsonNode variant : variants) {
            if (contains(variant.path("properties").path("type").path("enum"), discriminator)) {
                return true;
            }
        }
        return false;
    }

    private static @NonNull JsonNode actionVariantForTool(
            @NonNull JsonNode variants, @NonNull String toolName) {
        for (JsonNode variant : variants) {
            if (contains(variant.path("properties").path("tool").path("enum"), toolName)) {
                return variant;
            }
        }
        fail("missing guided action variant for " + toolName);
        throw new AssertionError("unreachable");
    }
}
