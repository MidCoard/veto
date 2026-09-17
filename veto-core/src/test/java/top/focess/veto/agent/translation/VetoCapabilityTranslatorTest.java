package top.focess.veto.agent.translation;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import top.focess.veto.agent.tool.ToolSchemaCompiler;
import top.focess.veto.agent.tool.builtin.AnswerWithCitationsTool;
import top.focess.veto.agent.tool.builtin.LoadSkillTool;
import top.focess.veto.agent.tool.builtin.SubmitPlanTool;
import top.focess.veto.agent.tool.builtin.ViewFileTool;
import top.focess.veto.llm.core.ToolDefinition;

/**
 * Validates {@link VetoCapabilityTranslator} against the per-turn veto_pulse variant matrix and the
 * manifest to flat tool translation.
 */
class VetoCapabilityTranslatorTest {

    private final @NonNull VetoCapabilityTranslator translator = new VetoCapabilityTranslator();

    @Test
    void translateToolsFlattensManifestToNameDescriptionSchema() {
        // Java class literals are non-null; Checker treats this literal as nullable.
        @SuppressWarnings("nullness:assignment")
        @NonNull Class<?> toolClass = LoadSkillTool.class;
        NativeToolDefinition nativeDef =
                new NativeToolDefinition(
                        "view_file",
                        "Read a file.",
                        ToolCapability.WORKSPACE_READ,
                        Danger.SAFE,
                        false,
                        toolClass,
                        ToolDocs.nonNullClass(LoadSkillTool.Args.class),
                        Map.<String, ParamCategory>of());
        AgentToolDefinition agent =
                new AgentToolDefinition(
                        "load_skill",
                        "Load a skill.",
                        ToolCapability.SKILL_READ,
                        Danger.SAFE,
                        toolClass,
                        ToolDocs.nonNullClass(LoadSkillTool.Args.class),
                        Map.<String, ParamCategory>of());
        List<ToolDefinition> flat = translator.translateTools(List.of(nativeDef, agent));
        assertEquals(2, flat.size());
        assertEquals("load_skill", flat.get(0).name());
        assertEquals("view_file", flat.get(1).name());
        assertEquals("Read a file.", flat.get(1).description());
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
    void translatedPlanBindsAllowedToolNamesAndInputsAndExcludesResponseSubmissions() {
        var tool = ToolSchemaCompiler.compileNative(new ViewFileTool());
        var plan = planDefinition("renamed_plan_submission");
        var answer =
                AgentToolDefinition.from(
                        "renamed_answer_submission",
                        ToolDocs.nonNullClass(AnswerWithCitationsTool.class),
                        ToolDocs.nonNullClass(AnswerWithCitationsTool.Args.class),
                        ToolCapability.LOOP_CONTROL);
        var flat = translator.translateTools(List.of(tool, plan, answer));
        JsonNode variants =
                new ObjectMapper()
                        .valueToTree(flat.get(1).inputSchema())
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
        assertEquals(
                List.of("view_file"),
                java.util.stream.StreamSupport.stream(variants.spliterator(), false)
                        .filter(
                                variant ->
                                        contains(
                                                variant.path("properties")
                                                        .path("type")
                                                        .path("enum"),
                                                "tool"))
                        .map(
                                variant ->
                                        variant.path("properties")
                                                .path("tool")
                                                .path("enum")
                                                .get(0)
                                                .asText())
                        .toList());
        assertEquals(
                flat,
                new DefaultCapabilityTranslator(new ObjectMapper())
                        .translateTools(List.of(tool, plan, answer)),
                "Reduced contexts must emit the same contextual native contracts");
    }

    @Test
    void planWithoutExecutableCatalogToolsStillAllowsGenerateButNoArbitraryToolNames() {
        var flat = translator.translateTools(List.of(planDefinition("submit_plan")));
        JsonNode variants =
                new ObjectMapper()
                        .valueToTree(flat.getFirst().inputSchema())
                        .path("properties")
                        .path("actions")
                        .path("items")
                        .path("anyOf");
        assertFalse(hasDiscriminator(variants, "tool"));
        assertTrue(hasDiscriminator(variants, "generate"));
        assertTrue(hasDiscriminator(variants, "STOP"));
    }

    private static @NonNull AgentToolDefinition planDefinition(@NonNull String name) {
        return AgentToolDefinition.from(
                name,
                ToolDocs.nonNullClass(SubmitPlanTool.class),
                ToolDocs.nonNullClass(SubmitPlanTool.Args.class),
                ToolCapability.LOOP_CONTROL);
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
