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
import top.focess.veto.agent.mcp.tools.LoadSkillArgs;

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
    void translateToolsFlattensManifestToNameDescriptionSchema() {
        NativeToolDefinition nativeDef =
                new NativeToolDefinition(
                        "view_file",
                        "Read a file.",
                        RiskCategory.READ_ONLY,
                        false,
                        ToolDocs.nonNullClass(LoadSkillArgs.class),
                        Map.<String, ParamCategory>of());
        AgentToolDefinition agent =
                new AgentToolDefinition(
                        "load_skill",
                        "Load a skill.",
                        ToolDocs.nonNullClass(LoadSkillArgs.class),
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
                flat.get(0).longDescription().isEmpty(),
                "native tool @ToolDoc longDescription flows through translateTools");
        assertFalse(
                flat.get(1).longDescription().isEmpty(),
                "agent tool @ToolDoc longDescription flows through translateTools");
    }

    private static boolean contains(JsonNode array, @NonNull String value) {
        if (array == null || !array.isArray()) return false;
        for (JsonNode n : array) {
            if (value.equals(n.asText())) return true;
        }
        return false;
    }
}
