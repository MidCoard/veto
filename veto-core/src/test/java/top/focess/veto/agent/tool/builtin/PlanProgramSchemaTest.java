package top.focess.veto.agent.tool.builtin;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.tool.NativeToolArgumentValidator;
import top.focess.veto.agent.tool.ToolExecutionException;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.agent.tool.ToolDocumentation;
import top.focess.veto.llm.core.ToolDefinition;

class PlanProgramSchemaTest {
    private static final @NonNull ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void generationResponseModeDefaultsToTextAndAcceptsOnlyDeclaredChannels() throws Exception {
        var schema = PlanProgramSchema.create(List.of(), Set.of(), true);
        var mode = schema.at("/properties/actions/items/anyOf/0/properties/response_mode");
        assertEquals("TEXT", mode.path("default").asText());
        assertEquals(
                List.of("TEXT", "CITATIONS"), MAPPER.convertValue(mode.path("enum"), List.class));
        for (String field :
                List.of("", ",\"response_mode\":\"TEXT\"", ",\"response_mode\":\"CITATIONS\"")) {
            var args = generationArguments(field);
            assertDoesNotThrow(
                    () ->
                            NativeToolArgumentValidator.validateAgainstSchema(
                                    "submit_plan", args, schema));
        }
        for (String value : List.of("null", "true", "1", "\"text\"", "\"AUTO\"")) {
            var args = generationArguments(",\"response_mode\":" + value);
            assertThrows(
                    ToolDocs.nonNullClass(ToolExecutionException.class),
                    () ->
                            NativeToolArgumentValidator.validateAgainstSchema(
                                    "submit_plan", args, schema));
        }
    }

    @Test
    void generationCannotRequestCitationsWithoutAnAnswerCapability() throws Exception {
        var schema = PlanProgramSchema.create(List.of(), Set.of(), false);
        var mode = schema.at("/properties/actions/items/anyOf/0/properties/response_mode");
        assertEquals(1, mode.path("enum").size());
        assertEquals("TEXT", mode.path("enum").get(0).asText());
        assertFalse(mode.path("description").asText().contains("CITATIONS"));
        assertDoesNotThrow(
                () ->
                        NativeToolArgumentValidator.validateAgainstSchema(
                                "submit_plan", generationArguments(""), schema));
        assertThrows(
                ToolDocs.nonNullClass(ToolExecutionException.class),
                () ->
                        NativeToolArgumentValidator.validateAgainstSchema(
                                "submit_plan",
                                generationArguments(",\"response_mode\":\"CITATIONS\""),
                                schema));
    }

    private @NonNull JsonNode generationArguments(@NonNull String modeField) throws Exception {
        return MAPPER.readTree(
                """
                {"actions":[{"id":"draft","label":"Draft","type":"generate",
                  "prompt":"Summarize the supplied source.","inputs":{"source":"$source"},
                  "outputs":{"answer":"message"}%s},
                  {"id":"done","label":"Done","type":"STOP","result_binding":"answer"}]}
                """
                        .formatted(modeField));
    }

    private @NonNull JsonNode planSchema(@NonNull String toolSchema) throws Exception {
        var tool =
                new ToolDefinition(
                        "remote_fixture",
                        "Remote fixture",
                        MAPPER.readValue(toolSchema, new TypeReference<Map<String, Object>>() {}),
                        List.of(),
                        ToolDocumentation.empty(),
                        List.of(),
                        List.of());
        return PlanProgramSchema.create(List.of(tool));
    }

    private @NonNull JsonNode inputsSchema(@NonNull JsonNode plan) {
        return plan.at("/properties/actions/items/anyOf/0/properties/inputs");
    }

    private void validate(@NonNull JsonNode plan, @NonNull String inputs) throws Exception {
        var arguments =
                MAPPER.readTree(
                        """
                {"actions":[{"id":"step","label":"Run","type":"tool","tool":"remote_fixture",
                "inputs":%s,"outputs":{}},{"id":"done","label":"Done","type":"STOP"}]}
                """
                                .formatted(inputs));
        NativeToolArgumentValidator.validateAgainstSchema("submit_plan", arguments, plan);
    }

    @Test
    void retainsDictionaryKeysAndValueConstraints() throws Exception {
        var plan =
                planSchema(
                        """
                {"type":"object","minProperties":1,"additionalProperties":{"type":"integer","minimum":0}}
                """);
        var inputs = inputsSchema(plan);
        assertEquals(1, inputs.path("minProperties").asInt());
        assertTrue(inputs.path("additionalProperties").isObject());
        assertDoesNotThrow(() -> validate(plan, "{\"first\":1,\"second\":\"$count\"}"));
        assertThrows(
                ToolDocs.nonNullClass(ToolExecutionException.class),
                () -> validate(plan, "{\"first\":-1}"));
        assertThrows(
                ToolDocs.nonNullClass(ToolExecutionException.class),
                () -> validate(plan, "{\"first\":true}"));
    }

    @Test
    void retainsRootUnionAndEachRequiredLiteralContract() throws Exception {
        var plan =
                planSchema(
                        """
                {"type":"object","anyOf":[
                  {"type":"object","properties":{"kind":{"enum":["lookup"]},"key":{"type":"string"}},
                   "required":["kind","key"],"additionalProperties":false},
                  {"type":"object","properties":{"kind":{"enum":["list"]},"limit":{"type":"integer"}},
                   "required":["kind","limit"],"additionalProperties":false}]}
                """);
        assertEquals(2, inputsSchema(plan).path("anyOf").size());
        assertDoesNotThrow(() -> validate(plan, "{\"kind\":\"lookup\",\"key\":\"known\"}"));
        assertDoesNotThrow(() -> validate(plan, "{\"kind\":\"list\",\"limit\":\"$count\"}"));
        assertThrows(
                ToolDocs.nonNullClass(ToolExecutionException.class),
                () -> validate(plan, "{\"kind\":\"lookup\"}"));
        assertThrows(
                ToolDocs.nonNullClass(ToolExecutionException.class),
                () -> validate(plan, "{\"kind\":\"list\",\"limit\":null}"));
        assertThrows(
                ToolDocs.nonNullClass(ToolExecutionException.class),
                () -> validate(plan, "\"$all_inputs\""));
    }

    @Test
    void resolvesAcyclicReferencesAgainstToolRootBeforeEmbedding() throws Exception {
        var plan =
                planSchema(
                        """
                {"$defs":{"args":{"type":"object","properties":{"count":{"$ref":"#/$defs/count"}},
                            "required":["count"],"additionalProperties":false},
                          "count":{"type":"integer","minimum":1}},"$ref":"#/$defs/args"}
                """);
        assertFalse(inputsSchema(plan).toString().contains("$ref"));
        assertFalse(inputsSchema(plan).has("$defs"));
        assertDoesNotThrow(() -> validate(plan, "{\"count\":2}"));
        assertDoesNotThrow(() -> validate(plan, "{\"count\":\"$count\"}"));
        assertThrows(
                ToolDocs.nonNullClass(ToolExecutionException.class),
                () -> validate(plan, "{\"count\":0}"));
        assertThrows(
                ToolDocs.nonNullClass(ToolExecutionException.class), () -> validate(plan, "{}"));
    }

    @Test
    void preservesPatternDictionaryAndLiteralConstraints() throws Exception {
        var plan =
                planSchema(
                        """
                {"type":"object","patternProperties":{"^entry_":{"type":"integer","minimum":0}},
                 "additionalProperties":false}
                """);
        assertDoesNotThrow(() -> validate(plan, "{\"entry_first\":1,\"entry_next\":\"$count\"}"));
        assertThrows(
                ToolDocs.nonNullClass(ToolExecutionException.class),
                () -> validate(plan, "{\"unknown\":1}"));
        assertThrows(
                ToolDocs.nonNullClass(ToolExecutionException.class),
                () -> validate(plan, "{\"entry_first\":false}"));
    }

    @Test
    void remoteOptionalFieldsKeepTheirDeclaredNullability() throws Exception {
        var plan =
                planSchema(
                        """
                {"type":"object","properties":{"optional":{"type":"string"},
                 "nullable":{"type":["string","null"]}},"additionalProperties":false}
                """);
        assertDoesNotThrow(() -> validate(plan, "{}"));
        assertDoesNotThrow(() -> validate(plan, "{\"nullable\":null}"));
        assertThrows(
                ToolDocs.nonNullClass(ToolExecutionException.class),
                () -> validate(plan, "{\"optional\":null}"));
    }

    @Test
    void recursiveAndExternalReferencesDeferOnlyUnresolvedFragment() throws Exception {
        var plan =
                planSchema(
                        """
                {"type":"object","properties":{
                  "tree":{"$ref":"#/$defs/node"},
                  "external":{"$ref":"https://example.invalid/schema","type":"string","minLength":2}},
                 "required":["tree","external"],"additionalProperties":false,
                 "$defs":{"node":{"type":"object","properties":{"name":{"type":"string"},
                                      "child":{"$ref":"#/$defs/node","type":"object"}},
                                    "required":["name"],"additionalProperties":false}}}
                """);
        assertFalse(inputsSchema(plan).toString().contains("$ref"));
        assertDoesNotThrow(
                () ->
                        validate(
                                plan,
                                "{\"tree\":{\"name\":\"root\",\"child\":{\"name\":\"leaf\"}},\"external\":\"ok\"}"));
        assertThrows(
                ToolDocs.nonNullClass(ToolExecutionException.class),
                () -> validate(plan, "{\"tree\":{},\"external\":\"ok\"}"));
        assertThrows(
                ToolDocs.nonNullClass(ToolExecutionException.class),
                () ->
                        validate(
                                plan,
                                "{\"tree\":{\"name\":\"root\",\"child\":12},\"external\":\"ok\"}"));
        assertThrows(
                ToolDocs.nonNullClass(ToolExecutionException.class),
                () -> validate(plan, "{\"tree\":{\"name\":\"root\"},\"external\":\"x\"}"));
    }
}
