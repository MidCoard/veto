package top.focess.veto.agent.tool;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.tool.builtin.SubmitPlanTool;

class NativeToolArgumentValidatorTest {
    private final @NonNull ObjectMapper mapper = new ObjectMapper();

    @Test
    void constraintDiagnosticsPreserveTheFieldWithoutEchoingRejectedValues() throws Exception {
        var schema =
                mapper.readTree(
                        """
                {"type":"object","properties":{"mode":{"type":"string","enum":["brief","full"]}},"required":["mode"]}
                """);
        var arguments = mapper.readTree("{\"mode\":\"malformed-secret-value\"}");
        var error =
                assertThrows(
                        ToolDocs.nonNullClass(ToolExecutionException.class),
                        () ->
                                NativeToolArgumentValidator.validateAgainstSchema(
                                        "sample", arguments, schema));
        String message = String.valueOf(error.getMessage());
        assertTrue(message.contains("parameter 'mode' must be one of"), message);
        assertTrue(message.contains("brief"), message);
        assertFalse(message.contains("malformed-secret-value"), message);
    }

    @Test
    void misplacedDiscriminatorDoesNotInventAnUnrelatedVariant() throws Exception {
        var error =
                invalidPlan(
                        """
                {"actions":[{"id":"draft","label":"Draft","outputs":{
                  "type":"generate","prompt":"Summarize the notes","answer":"message"}},
                  {"id":"end","label":"Return","type":"STOP","result_binding":"answer"}]}
                """);
        assertTrue(error.contains("missing required discriminator 'actions[0].type'"), error);
        assertTrue(error.contains("actions[0].outputs.type"), error);
        assertFalse(error.contains("unknown parameter 'actions[0].outputs'"), error);
    }

    @Test
    void knownDiscriminatorReportsErrorsFromItsOwnVariant() throws Exception {
        var error =
                invalidPlan(
                        """
                {"actions":[{"id":"draft","label":"Draft","type":"generate",
                "outputs":{"prompt":"Write a summary","answer":"message"}},
                {"id":"end","label":"Return","type":"STOP","result_binding":"answer"}]}
                """);
        assertTrue(error.contains("missing required parameter 'actions[0].prompt'"), error);
        assertTrue(error.contains("actions[0].outputs.prompt"), error);
        assertFalse(error.contains("unknown parameter 'actions[0].outputs'"), error);
    }

    @Test
    void unknownDiscriminatorDoesNotMasqueradeAsAValidAction() throws Exception {
        var error =
                invalidPlan(
                        """
                {"actions":[{"id":"x","label":"x","type":"invented"}]}
                """);
        assertTrue(error.contains("discriminator 'actions[0].type'"), error);
        assertTrue(error.contains("generate"), error);
    }

    @Test
    void nullAlternativeIsNotAWildcardForInvalidValues() throws Exception {
        JsonNode schema =
                mapper.readTree(
                        """
                {"type":"object","properties":{"count":{"anyOf":[
                  {"type":"integer"},{"type":"null"}]}},"required":["count"]}
                """);
        for (String json : List.of("{\"count\":null}", "{\"count\":2}"))
            assertDoesNotThrow(
                    () ->
                            NativeToolArgumentValidator.validateAgainstSchema(
                                    "sample", mapper.readTree(json), schema));
        for (String json : List.of("{\"count\":\"wrong\"}", "{\"count\":{}}", "{\"count\":true}"))
            assertThrows(
                    ToolDocs.nonNullClass(ToolExecutionException.class),
                    () ->
                            NativeToolArgumentValidator.validateAgainstSchema(
                                    "sample", mapper.readTree(json), schema));
    }

    @Test
    void referencesAndEscapesRemainCorrectThroughUnions() throws Exception {
        JsonNode schema =
                mapper.readTree(
                        """
                {"type":"object","properties":{"items":{"type":"array","items":{"anyOf":[
                  {"type":"object","properties":{"kind":{"type":"string","enum":["amount"]},"value":{"type":"integer"}},"required":["kind","value"],"additionalProperties":false},
                  {"type":"object","properties":{"kind":{"type":"string","enum":["literal"]},"value":{"type":"string","enum":["$price"]}},"required":["kind","value"],"additionalProperties":false}
                ]}}},"required":["items"]}
                """);
        var valid =
                mapper.readTree(
                        """
                {"items":[{"kind":"amount","value":"$unknown"},{"kind":"literal","value":"$$price"}]}
                """);
        assertDoesNotThrow(
                () ->
                        NativeToolArgumentValidator.validateAgainstSchema(
                                "sample", valid, schema, true));
        var bad =
                mapper.readTree(
                        """
                {"items":[{"kind":"amount","value":"$unknown"},{"kind":"amount","value":"wrong"}]}
                """);
        var error =
                assertThrows(
                        ToolDocs.nonNullClass(ToolExecutionException.class),
                        () ->
                                NativeToolArgumentValidator.validateAgainstSchema(
                                        "sample", bad, schema, true));
        assertTrue(String.valueOf(error.getMessage()).contains("items[1].value"));
        var doubleEscaped =
                mapper.readTree("{\"items\":[{\"kind\":\"literal\",\"value\":\"$$$price\"}]}");
        assertThrows(
                ToolDocs.nonNullClass(ToolExecutionException.class),
                () ->
                        NativeToolArgumentValidator.validateAgainstSchema(
                                "sample", doubleEscaped, schema, true));
    }

    @Test
    void optionalTypeUnionsAndBindingNamesKeepTheirConstraints() throws Exception {
        var schema =
                mapper.readTree(
                        "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":[\"integer\",\"null\"]}}}");
        assertThrows(
                ToolDocs.nonNullClass(ToolExecutionException.class),
                () ->
                        NativeToolArgumentValidator.validateAgainstSchema(
                                "sample", mapper.readTree("{\"x\":false}"), schema));
        assertTrue(
                invalidPlan(
                                """
                {"actions":[{"id":"g","label":"Draft","type":"generate","prompt":"Hello",
                  "outputs":{"CURRENT_STEPS":"message"}},{"id":"s","label":"End","type":"STOP"}]}
                """)
                        .contains("actions[0].outputs.CURRENT_STEPS"));
    }

    @Test
    void unionSiblingsNullEnumsAndFalseSchemasAreNotSilentlyIgnored() throws Exception {
        for (String child :
                List.of(
                        "{\"type\":\"string\",\"anyOf\":[{\"minLength\":2},{\"const\":\"x\"}]}",
                        "{\"type\":[\"string\",\"null\"],\"enum\":[\"x\"]}",
                        "false")) {
            var schema =
                    mapper.readTree("{\"type\":\"object\",\"properties\":{\"x\":" + child + "}}");
            String invalid = child.contains("enum") ? "{\"x\":null}" : "{\"x\":42}";
            assertThrows(
                    ToolDocs.nonNullClass(ToolExecutionException.class),
                    () ->
                            NativeToolArgumentValidator.validateAgainstSchema(
                                    "sample", mapper.readTree(invalid), schema));
        }
    }

    @Test
    void requiredDiscriminatorCanExplicitlySelectANullVariant() throws Exception {
        var schema =
                mapper.readTree(
                        """
                {"type":"object","anyOf":[
                  {"properties":{"kind":{"type":"null","enum":[null]}},"required":["kind"]},
                  {"properties":{"kind":{"type":"string","enum":["named"]}},"required":["kind"]}
                ]}
                """);
        assertDoesNotThrow(
                () ->
                        NativeToolArgumentValidator.validateAgainstSchema(
                                "sample", mapper.readTree("{\"kind\":null}"), schema));
        assertThrows(
                ToolDocs.nonNullClass(ToolExecutionException.class),
                () ->
                        NativeToolArgumentValidator.validateAgainstSchema(
                                "sample", mapper.readTree("{}"), schema));
    }

    private @NonNull String invalidPlan(@NonNull String json) throws Exception {
        var args = mapper.readTree(json);
        var error =
                assertThrows(
                        ToolDocs.nonNullClass(ToolExecutionException.class),
                        () ->
                                NativeToolArgumentValidator.validate(
                                        "submit_plan",
                                        args,
                                        ToolDocs.nonNullClass(SubmitPlanTool.Args.class)));
        return String.valueOf(error.getMessage());
    }
}
