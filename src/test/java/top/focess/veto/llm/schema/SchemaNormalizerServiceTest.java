package top.focess.veto.llm.schema;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import top.focess.veto.llm.core.ToolDefinition;

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

    @Test
    @SuppressWarnings("unchecked")
    void testBuildOpenAIResponseSchema() {
        List<ToolDefinition> tools =
                List.of(
                        new ToolDefinition(
                                "list_files",
                                "List files in dir",
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of("path", Map.of("type", "string")))));

        Map<String, Object> schema = normalizer.buildOpenAIResponseSchema(tools);

        assertEquals("object", schema.get("type"));
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertTrue(properties.containsKey("thought"));
        assertTrue(properties.containsKey("call"));
        assertTrue(properties.containsKey("is_finished"));

        assertEquals(false, schema.get("additionalProperties"));
    }
}
