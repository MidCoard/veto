package top.focess.veto.llm.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;

class OpenAiStrictSchemaAdapterTest {

    private final @NonNull ObjectMapper mapper = new ObjectMapper();

    @Test
    void makesOptionalPropertiesRequiredAndNullable() throws Exception {
        JsonNode canonical =
                mapper.readTree(
                        """
                        {
                          "type": "object",
                          "properties": {
                            "message": {"type": "string"},
                            "features": {
                              "type": "object",
                              "properties": {"guided": {"type": "boolean"}},
                              "required": ["guided"],
                              "additionalProperties": false
                            }
                          },
                          "required": ["features"],
                          "additionalProperties": false
                        }
                        """);

        OpenAiStrictSchemaAdapter.Adapted adapted = OpenAiStrictSchemaAdapter.adapt(canonical);

        assertTrue(adapted.strict());
        assertTrue(contains(adapted.schema().path("required"), "message"));
        assertTrue(contains(adapted.schema().path("required"), "features"));
        assertTrue(
                containsType(
                        adapted.schema().path("properties").path("message").path("anyOf"), "null"));
        assertFalse(
                adapted.schema()
                        .path("properties")
                        .path("features")
                        .path("additionalProperties")
                        .asBoolean());
    }

    @Test
    void dynamicMapsKeepCanonicalSchemaAndDisableStrictMode() throws Exception {
        JsonNode canonical =
                mapper.readTree(
                        """
                        {
                          "type": "object",
                          "properties": {
                            "outputs": {
                              "type": "object",
                              "additionalProperties": {"type": "string"}
                            }
                          },
                          "required": ["outputs"],
                          "additionalProperties": false
                        }
                        """);

        OpenAiStrictSchemaAdapter.Adapted adapted = OpenAiStrictSchemaAdapter.adapt(canonical);

        assertFalse(adapted.strict());
        assertTrue(
                adapted.schema()
                        .path("properties")
                        .path("outputs")
                        .path("additionalProperties")
                        .isObject());
    }

    private static boolean contains(@NonNull JsonNode array, @NonNull String value) {
        for (JsonNode item : array) {
            if (value.equals(item.asText())) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsType(@NonNull JsonNode array, @NonNull String type) {
        for (JsonNode item : array) {
            if (type.equals(item.path("type").asText())) {
                return true;
            }
        }
        return false;
    }
}
