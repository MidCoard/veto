package top.focess.veto.llm.schema;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.translation.DefaultCapabilityTranslator;

class SchemaNormalizerServiceTest {

    private final SchemaNormalizerService normalizer = new SchemaNormalizerService();

    @Test
    @SuppressWarnings("unchecked")
    void testNormalizeForOpenAI() {
        Map<String, Object> schema =
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of(
                                "name", Map.of("type", "string"),
                                "age", Map.of("type", "integer")));

        Map<String, Object> normalized = normalizer.normalizeForOpenAI(schema);

        assertEquals(false, normalized.get("additionalProperties"));
        List<String> required = (List<String>) normalized.get("required");
        assertTrue(required.contains("name"));
        assertTrue(required.contains("age"));
        assertEquals(2, required.size());
    }

    /**
     * The per-turn {@code veto_pulse} response schema is now built by the {@link
     * DefaultCapabilityTranslator} (the old single-{@code call} builder that lived in {@link
     * SchemaNormalizerService} is retired). This adapts the retired assertion to the new shape:
     * thought-ON / autonomous variant carries {@code thought}, {@code calls}, {@code message},
     * {@code is_finished}, {@code features} — never the legacy single {@code call} field, and never
     * {@code actionsProgram} (that is the guided-switch variant only).
     */
    @Test
    @SuppressWarnings("unchecked")
    void testVetoPulseResponseSchemaAutonomousThoughtOn() {
        ObjectMapper mapper = new ObjectMapper();
        DefaultCapabilityTranslator translator = new DefaultCapabilityTranslator(mapper);

        Map<String, Object> schema =
                mapper.convertValue(translator.vetoResponseSchema(true, false), Map.class);

        assertEquals("object", schema.get("type"));
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertTrue(properties.containsKey("thought"), "thought-ON variant must carry thought");
        assertTrue(properties.containsKey("calls"), "autonomous variant must carry calls");
        assertTrue(properties.containsKey("message"));
        assertTrue(properties.containsKey("is_finished"));
        assertTrue(properties.containsKey("features"));
        assertFalse(properties.containsKey("call"), "legacy single-call field is retired");
        assertFalse(properties.containsKey("actionsProgram"), "autonomous forbids actionsProgram");

        assertEquals(false, schema.get("additionalProperties"));
        List<String> required = (List<String>) schema.get("required");
        assertTrue(required.contains("is_finished"));
        assertTrue(required.contains("features"));
        assertTrue(required.contains("thought"), "thought required when thought-ON");
    }

    /**
     * The guided-switch variant flips the schema: {@code thought} is forbidden, {@code calls} is
     * forbidden, and {@code actionsProgram} is required.
     */
    @Test
    @SuppressWarnings("unchecked")
    void testVetoPulseResponseSchemaGuidedThoughtOff() {
        ObjectMapper mapper = new ObjectMapper();
        DefaultCapabilityTranslator translator = new DefaultCapabilityTranslator(mapper);

        Map<String, Object> schema =
                mapper.convertValue(translator.vetoResponseSchema(false, true), Map.class);
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertFalse(properties.containsKey("thought"), "thought-OFF forbids thought");
        assertFalse(properties.containsKey("calls"), "guided forbids calls");
        assertTrue(properties.containsKey("actionsProgram"), "guided requires actionsProgram");
        assertTrue(properties.containsKey("message"));
        List<String> required = (List<String>) schema.get("required");
        assertTrue(required.contains("actionsProgram"));
        assertTrue(required.contains("message"), "message required when thought-OFF");
    }
}
