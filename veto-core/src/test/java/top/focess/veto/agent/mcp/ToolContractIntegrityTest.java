package top.focess.veto.agent.mcp;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Cross-checks every documented call example against the tool's real runtime argument validator.
 */
@SpringBootTest
@SuppressWarnings("initialization.field.uninitialized")
class ToolContractIntegrityTest {

    @Autowired private @NonNull List<NativeTool<?>> nativeTools;
    @Autowired private @NonNull List<AgentTool<?>> agentTools;

    private final @NonNull ObjectMapper mapper = new ObjectMapper();

    @Test
    void everyCallExamplePassesItsRuntimeArgumentValidator() {
        for (NativeTool<?> tool : nativeTools) {
            validateExamples(tool.getName(), tool.getArgsClass());
        }
        for (AgentTool<?> tool : agentTools) {
            validateExamples(tool.getName(), tool.getArgsClass());
        }
    }

    @Test
    void everyDeclaredRequiredParameterIsRejectedCentrallyWhenMissingOrNull() {
        for (NativeTool<?> tool : nativeTools) {
            verifyRequiredParameters(tool.getName(), tool.getArgsClass());
        }
        for (AgentTool<?> tool : agentTools) {
            verifyRequiredParameters(tool.getName(), tool.getArgsClass());
        }
    }

    private void validateExamples(@NonNull String toolName, @NonNull Class<?> argsClass) {
        for (String example : ToolDocs.examplesOf(argsClass)) {
            assertDoesNotThrow(
                    () -> {
                        JsonNode args = mapper.readTree(example);
                        NativeToolArgumentValidator.validate(toolName, args, argsClass);
                        mapper.treeToValue(args, argsClass);
                    },
                    () -> toolName + " has an invalid call example: " + example);
        }
    }

    private void verifyRequiredParameters(@NonNull String toolName, @NonNull Class<?> argsClass) {
        JsonNode schema = ToolSchemaCompiler.compileFromRecord(argsClass);
        ObjectNode complete = exampleArguments(argsClass, schema);
        assertDoesNotThrow(
                () -> NativeToolArgumentValidator.validate(toolName, complete, argsClass),
                () -> toolName + " synthesized valid arguments were rejected");

        List<RequiredPath> requiredPaths = new ArrayList<>();
        collectRequiredPaths(schema, complete, "", "", requiredPaths);
        for (RequiredPath required : requiredPaths) {

            ObjectNode missing = complete.deepCopy();
            JsonNode missingParent = missing.at(required.parentPointer());
            assertTrue(missingParent instanceof ObjectNode);
            ((ObjectNode) missingParent).remove(required.name());
            NativeToolArgumentValidator.InvalidArgumentsException missingFailure =
                    assertThrows(
                            ToolDocs.nonNullClass(
                                    NativeToolArgumentValidator.InvalidArgumentsException.class),
                            () ->
                                    NativeToolArgumentValidator.validate(
                                            toolName, missing, argsClass),
                            () ->
                                    toolName
                                            + "."
                                            + required.displayPath()
                                            + " reached dispatch while missing");
            assertTrue(
                    String.valueOf(missingFailure.getMessage())
                            .contains(
                                    "missing required parameter '" + required.displayPath() + "'"));

            ObjectNode explicitNull = complete.deepCopy();
            JsonNode nullParent = explicitNull.at(required.parentPointer());
            assertTrue(nullParent instanceof ObjectNode);
            ((ObjectNode) nullParent).putNull(required.name());
            NativeToolArgumentValidator.InvalidArgumentsException nullFailure =
                    assertThrows(
                            ToolDocs.nonNullClass(
                                    NativeToolArgumentValidator.InvalidArgumentsException.class),
                            () ->
                                    NativeToolArgumentValidator.validate(
                                            toolName, explicitNull, argsClass),
                            () ->
                                    toolName
                                            + "."
                                            + required.displayPath()
                                            + " reached dispatch as null");
            assertTrue(
                    String.valueOf(nullFailure.getMessage())
                            .contains(
                                    "missing required parameter '" + required.displayPath() + "'"));
        }
    }

    private void collectRequiredPaths(
            @NonNull JsonNode schema,
            @NonNull JsonNode value,
            @NonNull String parentPointer,
            @NonNull String displayPath,
            @NonNull List<RequiredPath> requiredPaths) {
        if ("object".equals(schema.path("type").asText()) && value.isObject()) {
            for (JsonNode requiredName : schema.path("required")) {
                String name = requiredName.asText();
                requiredPaths.add(
                        new RequiredPath(parentPointer, name, childDisplay(displayPath, name)));
            }
            JsonNode properties = schema.path("properties");
            for (var field : properties.properties()) {
                String name = field.getKey();
                if (value.has(name) && !value.get(name).isNull()) {
                    collectRequiredPaths(
                            field.getValue(),
                            value.get(name),
                            parentPointer + "/" + escapePointer(name),
                            childDisplay(displayPath, name),
                            requiredPaths);
                }
            }
        } else if ("array".equals(schema.path("type").asText()) && value.isArray()) {
            for (int i = 0; i < value.size(); i++) {
                collectRequiredPaths(
                        schema.path("items"),
                        value.get(i),
                        parentPointer + "/" + i,
                        displayPath + "[" + i + "]",
                        requiredPaths);
            }
        }
    }

    private static @NonNull String childDisplay(@NonNull String parent, @NonNull String child) {
        return parent.isEmpty() ? child : parent + "." + child;
    }

    private static @NonNull String escapePointer(@NonNull String segment) {
        return segment.replace("~", "~0").replace("/", "~1");
    }

    private record RequiredPath(
            @NonNull String parentPointer, @NonNull String name, @NonNull String displayPath) {}

    private @NonNull ObjectNode exampleArguments(
            @NonNull Class<?> argsClass, @NonNull JsonNode schema) {
        List<String> examples = ToolDocs.examplesOf(argsClass);
        if (examples.isEmpty()) {
            return synthesizeObject(schema);
        }
        try {
            JsonNode parsed = mapper.readTree(String.valueOf(examples.get(0)));
            if (parsed instanceof ObjectNode object) {
                return object;
            }
            throw new AssertionError(argsClass.getName() + " has a non-object call example");
        } catch (Exception e) {
            throw new AssertionError(argsClass.getName() + " has an unreadable call example", e);
        }
    }

    private @NonNull ObjectNode synthesizeObject(@NonNull JsonNode schema) {
        ObjectNode object = mapper.createObjectNode();
        for (JsonNode requiredName : schema.path("required")) {
            String name = requiredName.asText();
            object.set(name, synthesizeValue(schema.path("properties").path(name)));
        }
        return object;
    }

    private @NonNull JsonNode synthesizeValue(@NonNull JsonNode schema) {
        JsonNode allowed = schema.path("enum");
        if (allowed.isArray() && !allowed.isEmpty()) {
            return allowed.get(0);
        }
        return switch (schema.path("type").asText()) {
            case "object" -> synthesizeObject(schema);
            case "array" -> mapper.createArrayNode();
            case "integer" -> mapper.getNodeFactory().numberNode(0);
            case "number" -> mapper.getNodeFactory().numberNode(0.0);
            case "boolean" -> mapper.getNodeFactory().booleanNode(false);
            default -> mapper.getNodeFactory().textNode("value");
        };
    }
}
