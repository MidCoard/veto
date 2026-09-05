package top.focess.veto.agent.translation;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.screening.Danger;
import top.focess.veto.agent.tool.AgentToolDefinition;
import top.focess.veto.agent.tool.NativeToolDefinition;
import top.focess.veto.agent.tool.ParamCategory;
import top.focess.veto.agent.tool.ToolCapability;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.agent.tool.ToolDocumentation;
import top.focess.veto.agent.tool.ToolResultFormat;
import top.focess.veto.agent.tool.builtin.LoadSkillTool;
import top.focess.veto.llm.core.ToolDefinition;

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
        assertFalse(contains(required, "features"));
    }

    @Test
    void enabledGuideIsOptionalAndKeepsNormalCallsAvailable() {
        JsonNode schema = translator.vetoResponseSchema(true);
        JsonNode props = schema.get("properties");
        JsonNode required = schema.get("required");
        assertTrue(props.has("calls"));
        assertTrue(props.has("guide"));
        JsonNode guide = props.path("guide");
        assertEquals(
                "array",
                guide.path("properties").path("actions").get("type").asText(),
                "actions is a flat array");
        assertFalse(contains(required, "guide"));
        assertTrue(contains(guide.path("required"), "actions"));
        assertFalse(contains(required, "features"));
        assertFalse(contains(required, "thought"), "thought never required");
        JsonNode actionVariants =
                guide.path("properties").path("actions").path("items").path("anyOf");
        assertEquals(4, actionVariants.size(), "four non-tool guided action kinds without tools");
        assertTrue(hasDiscriminator(actionVariants, "generate"));
        assertTrue(hasDiscriminator(actionVariants, "goto"));
        assertTrue(hasDiscriminator(actionVariants, "conditional_goto"));
        assertTrue(hasDiscriminator(actionVariants, "STOP"));
    }

    @Test
    void featureBagIsAbsentAndGuideIsOnlyAdvertisedWhenEnabled() {
        for (boolean enabled : new boolean[] {true, false}) {
            JsonNode props = translator.vetoResponseSchema(enabled).path("properties");
            assertFalse(props.has("features"));
            assertFalse(props.has("actions"));
            assertEquals(enabled, props.has("guide"));
            assertTrue(props.has("calls"));
            assertTrue(props.has("message"));
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
        List<ToolDefinition> tools =
                List.of(
                        new ToolDefinition(
                                "view_file",
                                "Read a file.",
                                viewArgs,
                                List.of(),
                                ToolDocumentation.empty(),
                                List.of(),
                                List.of(ToolResultFormat.PLAINTEXT)),
                        new ToolDefinition(
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
                        ToolCapability.WORKSPACE_READ,
                        Danger.SAFE,
                        false,
                        ToolDocs.nonNullClass(LoadSkillTool.Args.class),
                        Map.<String, ParamCategory>of());
        AgentToolDefinition agent =
                new AgentToolDefinition(
                        "load_skill",
                        "Load a skill.",
                        ToolCapability.SKILL_READ,
                        Danger.SAFE,
                        ToolDocs.nonNullClass(LoadSkillTool.Args.class),
                        Map.<String, ParamCategory>of());
        List<ToolDefinition> flat = translator.translateTools(List.of(nativeDef, agent));
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
                new ToolDefinition(
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
                        .path("guide")
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
                        .path("anyOf")
                        .get(0)
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
