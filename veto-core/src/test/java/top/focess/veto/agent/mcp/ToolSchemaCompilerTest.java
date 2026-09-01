package top.focess.veto.agent.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.mcp.tools.RunCommandTool;
import top.focess.veto.memory.MemoryTools;

/**
 * Validates {@link ToolSchemaCompiler#compileFromRecord}, in particular that nested record
 * components are reflected into the schema instead of being flattened to {@code items: string}.
 */
class ToolSchemaCompilerTest {

    @Test
    void nestedRecordCollectionGetsObjectItemsSchema() {
        JsonNode schema =
                ToolSchemaCompiler.compileFromRecord(
                        ToolDocs.nonNullClass(RunCommandTool.Args.class));

        JsonNode commands = schema.path("properties").path("commands");
        assertEquals("array", commands.path("type").asText(), "commands is an array");

        // The element type is the CommandInput record - its full object schema must be advertised,
        // not the old "items: [{type: string}]" lie that left the model guessing at the shape.
        JsonNode items = commands.path("items");
        assertTrue(items.isObject(), "items is a single schema object, not a tuple array");
        assertEquals("object", items.path("type").asText(), "commands holds objects");

        JsonNode args = items.path("properties").path("args");
        assertEquals("array", args.path("type").asText(), "CommandInput.args is an array");
        assertEquals(
                "string",
                args.path("items").path("type").asText(),
                "CommandInput.args items are strings");

        JsonNode executable = items.path("properties").path("executable");
        assertEquals(
                "string", executable.path("type").asText(), "CommandInput.executable is a string");

        // Both nested components are non-nullable, so they must be required inside the item schema.
        assertTrue(
                contains(items.path("required"), "executable"),
                "executable required in item schema");
        assertTrue(contains(items.path("required"), "args"), "args required in item schema");

        JsonNode network = schema.path("properties").path("network");
        assertEquals(
                "boolean",
                network.path("type").asText(),
                "network is a boolean capability request");
        assertFalse(contains(schema.path("required"), "network"), "network defaults to denied");
    }

    private static boolean contains(@NonNull JsonNode array, @NonNull String value) {
        if (!array.isArray()) return false;
        for (JsonNode n : array) {
            if (value.equals(n.asText())) return true;
        }
        return false;
    }

    /**
     * Sanity check the rendered schema is valid JSON (the compiler uses a private mapper; the
     * output must still be consumable by the shared LLM mapper that builds the manifest).
     */
    @Test
    void schemaRoundTripsThroughObjectMapper() throws Exception {
        JsonNode schema =
                ToolSchemaCompiler.compileFromRecord(
                        ToolDocs.nonNullClass(RunCommandTool.Args.class));
        String json = new ObjectMapper().writeValueAsString(schema);
        assertTrue(json.contains("\"commands\""), "serialized schema keeps commands");
        assertTrue(json.contains("\"executable\""), "serialized schema keeps nested executable");
    }

    @Test
    void forgetMemoryIdIsRequired() {
        JsonNode schema =
                ToolSchemaCompiler.compileFromRecord(
                        ToolDocs.nonNullClass(MemoryTools.Forget.Args.class));

        assertTrue(
                contains(schema.path("required"), "memoryId"),
                "forget must reject a missing memoryId before its handler runs");
    }

    @Test
    void enumArgumentIsRenderedAsAStringEnum() {
        JsonNode schema =
                ToolSchemaCompiler.compileFromRecord(
                        ToolDocs.nonNullClass(MemoryTools.WriteInsight.Args.class));

        JsonNode mode = schema.path("properties").path("mode");
        assertEquals("string", mode.path("type").asText());
        assertTrue(contains(mode.path("enum"), "WRITE"));
        assertTrue(contains(mode.path("enum"), "PROMOTE"));
        assertTrue(contains(schema.path("required"), "mode"));
    }
}
