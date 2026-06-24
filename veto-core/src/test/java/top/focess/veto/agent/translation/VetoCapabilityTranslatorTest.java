package top.focess.veto.agent.translation;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.mcp.AgentToolDefinition;
import top.focess.veto.agent.mcp.NativeToolDefinition;
import top.focess.veto.agent.mcp.ParamCategory;
import top.focess.veto.agent.mcp.RiskCategory;
import top.focess.veto.agent.mcp.tools.LoadSkillArgs;

/**
 * Validates {@link VetoCapabilityTranslator} against the per-turn veto_pulse variant matrix ({@code
 * prompt_react_syntax.md} §2.1.2) and the manifest→flat tool translation (§3).
 */
class VetoCapabilityTranslatorTest {

    private final VetoCapabilityTranslator translator = new VetoCapabilityTranslator();

    @Test
    void thoughtOnAutonomousHasThoughtCallsNoActionsProgram() {
        JsonNode schema = translator.vetoResponseSchema(true, false);
        JsonNode props = schema.get("properties");
        JsonNode required = schema.get("required");
        assertTrue(props.has("thought"), "thought present when thoughtRequired");
        assertTrue(props.has("calls"), "calls present when autonomous");
        assertFalse(props.has("actionsProgram"), "actionsProgram absent when not guided");
        assertTrue(contains(required, "thought"), "thought required when thoughtRequired");
        assertFalse(
                contains(required, "message"),
                "message optional when thoughtRequired (not finished)");
        assertTrue(contains(required, "is_finished"));
        assertTrue(contains(required, "features"));
    }

    @Test
    void thoughtOffAutonomousForbidsThoughtRequiresMessage() {
        JsonNode schema = translator.vetoResponseSchema(false, false);
        JsonNode props = schema.get("properties");
        JsonNode required = schema.get("required");
        assertFalse(props.has("thought"), "thought forbidden (absent) when !thoughtRequired");
        assertTrue(props.has("calls"));
        assertFalse(props.has("actionsProgram"));
        assertTrue(contains(required, "message"), "message required when !thoughtRequired");
        assertFalse(contains(required, "thought"));
    }

    @Test
    void guidedSwitchRequiresActionsProgramForbidsCalls() {
        JsonNode schema = translator.vetoResponseSchema(true, true);
        JsonNode props = schema.get("properties");
        JsonNode required = schema.get("required");
        assertFalse(props.has("calls"), "calls forbidden when guided");
        assertTrue(props.has("actionsProgram"), "actionsProgram present when guided");
        assertTrue(contains(required, "actionsProgram"), "actionsProgram required when guided");
    }

    @Test
    void thoughtOffGuidedForbidsThoughtRequiresMessageAndActionsProgram() {
        JsonNode schema = translator.vetoResponseSchema(false, true);
        JsonNode props = schema.get("properties");
        JsonNode required = schema.get("required");
        assertFalse(props.has("thought"));
        assertFalse(props.has("calls"));
        assertTrue(props.has("actionsProgram"));
        assertTrue(contains(required, "message"));
        assertTrue(contains(required, "actionsProgram"));
    }

    @Test
    void featuresAlwaysRequiredAndClosed() {
        for (boolean thought : new boolean[] {true, false}) {
            for (boolean guided : new boolean[] {true, false}) {
                JsonNode schema = translator.vetoResponseSchema(thought, guided);
                JsonNode features = schema.get("properties").get("features");
                assertEquals(false, features.get("additionalProperties").asBoolean());
                assertTrue(contains(features.get("required"), "guided"));
                assertTrue(contains(features.get("required"), "thought"));
            }
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
                        LoadSkillArgs.class,
                        Map.<String, ParamCategory>of());
        AgentToolDefinition agent =
                new AgentToolDefinition(
                        "load_skill",
                        "Load a skill.",
                        LoadSkillArgs.class,
                        Map.<String, ParamCategory>of());
        List<top.focess.veto.llm.core.ToolDefinition> flat =
                translator.translateTools(List.of(nativeDef, agent));
        assertEquals(2, flat.size());
        assertEquals("view_file", flat.get(0).name());
        assertEquals("Read a file.", flat.get(0).description());
        assertNotNull(flat.get(0).inputSchema());
        assertEquals("object", flat.get(0).inputSchema().get("type"));
    }

    private static boolean contains(JsonNode array, String value) {
        if (array == null || !array.isArray()) return false;
        for (JsonNode n : array) {
            if (value.equals(n.asText())) return true;
        }
        return false;
    }
}
