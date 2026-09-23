package top.focess.veto.providers;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.tool.builtin.PlanProgramSchema;

class AnthropicStrictToolPolicyTest {
    private final @NonNull ObjectMapper mapper = new ObjectMapper();

    @Test
    void compatibleSchemaUsesStrictWithoutChangingArguments() throws Exception {
        var schema =
                mapper.readTree(
                        """
                {"type":"object","properties":{"name":{"type":"string","pattern":"^[A-Za-z_][A-Za-z0-9_]*$"}},
                 "required":["name"],"additionalProperties":false}
                """);
        var original = schema.deepCopy();
        assertTrue(new AnthropicStrictToolPolicy().select(schema).strict());
        assertEquals(original, schema);
    }

    @Test
    void dynamicPlanAndFreeMapsKeepCanonicalSchemasWithNonStrictNativeCalls() throws Exception {
        var map =
                mapper.readTree(
                        """
                {"type":"object","properties":{"values":{"type":"object","additionalProperties":{"type":"string"}}},
                 "required":["values"],"additionalProperties":false}
                """);
        for (var schema : List.of(map, PlanProgramSchema.create(List.of()))) {
            var original = schema.deepCopy();
            assertFalse(new AnthropicStrictToolPolicy().select(schema).strict());
            assertEquals(
                    original,
                    schema,
                    "Strict eligibility must not close maps or remove constraints");
        }
    }

    @Test
    void unsupportedConstraintsAndAdvancedPatternsAreNotSentAsStrict() throws Exception {
        for (String parameter :
                List.of(
                        "{\"type\":\"string\",\"minLength\":1}",
                        "{\"type\":\"string\",\"maxLength\":5}",
                        "{\"type\":\"number\",\"minimum\":0}",
                        "{\"type\":\"number\",\"maximum\":2}",
                        "{\"type\":\"string\",\"pattern\":\"^(?!reserved$)[a-z]+$\"}",
                        "{\"type\":\"string\",\"pattern\":\"\\\\bword\\\\b\"}",
                        "{\"type\":\"string\",\"pattern\":\"(a)\\\\1\"}",
                        "{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"maxItems\":5}",
                        "{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"minItems\":2}",
                        "{\"type\":\"string\",\"enum\":[{\"x\":1}]}",
                        "{\"$ref\":\"#/$defs/value\"}")) {
            JsonNode schema = oneParameter(mapper.readTree(parameter), true);
            var decision = new AnthropicStrictToolPolicy().select(schema);
            assertFalse(decision.strict(), parameter);
        }
    }

    @Test
    void strictToolLimitCountsOnlyEligibleTools() throws Exception {
        var policy = new AnthropicStrictToolPolicy();
        var eligible = oneParameter(mapper.readTree("{\"type\":\"string\"}"), true);
        var ineligible = mapper.readTree("{\"type\":\"object\",\"additionalProperties\":true}");
        for (int i = 0; i < 20; i++) {
            assertFalse(policy.select(ineligible).strict());
            assertTrue(policy.select(eligible).strict());
        }
        var rejected = policy.select(eligible);
        assertFalse(rejected.strict());
        assertEquals("strict tool count limit", rejected.reason());
    }

    @Test
    void optionalParameterLimitIsAggregatedWithoutConsumingBudgetForRejectedTools()
            throws Exception {
        var policy = new AnthropicStrictToolPolicy();
        var fourOptional = object();
        for (int i = 0; i < 4; i++)
            fourOptional.withObject("/properties").putObject("p" + i).put("type", "string");
        for (int i = 0; i < 6; i++) assertTrue(policy.select(fourOptional).strict());
        var rejected = policy.select(fourOptional);
        assertFalse(rejected.strict());
        assertEquals("optional parameter count limit", rejected.reason());
        assertTrue(
                policy.select(oneParameter(mapper.readTree("{\"type\":\"string\"}"), true))
                        .strict());
    }

    @Test
    void unionLimitIncludesNestedAnyOfAndTypeArraysAcrossTools() throws Exception {
        var policy = new AnthropicStrictToolPolicy();
        var twoUnions = object();
        twoUnions
                .withObject("/properties")
                .set("first", mapper.readTree("{\"type\":[\"string\",\"null\"]}"));
        twoUnions
                .withObject("/properties")
                .set(
                        "second",
                        mapper.readTree(
                                "{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"integer\"}]}"));
        twoUnions.putArray("required").add("first").add("second");
        for (int i = 0; i < 8; i++) assertTrue(policy.select(twoUnions).strict());
        var rejected = policy.select(twoUnions);
        assertFalse(rejected.strict());
        assertEquals("union parameter count limit", rejected.reason());
        assertTrue(
                policy.select(oneParameter(mapper.readTree("{\"type\":\"string\"}"), true))
                        .strict());
    }

    private @NonNull ObjectNode oneParameter(@NonNull JsonNode parameter, boolean required) {
        var schema = object();
        schema.withObject("/properties").set("value", parameter);
        if (required) schema.putArray("required").add("value");
        return schema;
    }

    private @NonNull ObjectNode object() {
        var schema =
                mapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        schema.putObject("properties");
        return schema;
    }
}
