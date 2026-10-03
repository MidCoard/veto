package top.focess.veto.agent.tool;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import top.focess.veto.api.agent.tool.CapabilityTool;
import top.focess.veto.api.agent.tool.NativeTool;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.agent.tool.ToolErrorCode;
import top.focess.veto.api.agent.tool.ToolExecutionException;
import top.focess.veto.api.agent.tool.ToolSecurity;
import top.focess.veto.api.agent.tool.WorkspaceReadTool;
import top.focess.veto.api.agent.tool.WorkspaceWriteTool;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.integration.plugins.PluginManager;

/**
 * Cross-checks every documented call example against the tool's real runtime argument validator.
 */
@SpringBootTest
@SuppressWarnings("initialization.field.uninitialized")
class ToolContractIntegrityTest {

    @Autowired private @NonNull PluginManager plugins;
    private @NonNull List<CapabilityTool<?>> tools = List.of();
    private @NonNull List<NativeTool<?>> nativeTools = List.of();

    @BeforeEach
    void readProductionPluginCatalog() {
        var natives = new ArrayList<NativeTool<?>>();
        var contributed = new ArrayList<CapabilityTool<?>>();
        for (var entry : plugins.registry().entries(StandardContributionPoints.TOOLS)) {
            var tool = entry.implementation();
            if (tool instanceof CapabilityTool<?> local) contributed.add(local);
            if (tool instanceof NativeTool<?> nativeTool) natives.add(nativeTool);
        }
        tools = List.copyOf(contributed);
        nativeTools = List.copyOf(natives);
        assertEquals(
                plugins.registry().entries(StandardContributionPoints.TOOLS).stream()
                        .filter(entry -> entry.implementation() instanceof CapabilityTool<?>)
                        .count(),
                tools.size(),
                "every contributed handler must be checked");
        assertEquals(38, tools.size());
    }

    private final @NonNull ObjectMapper mapper = new ObjectMapper();

    @Test
    void productionCatalogDoesNotRegisterRedundantThinkingOrTestTools() {
        assertTrue(
                tools.stream()
                        .noneMatch(
                                tool ->
                                        tool.getName().equals("think")
                                                || tool.getName().equals("fixture_loop")));
    }

    @Test
    void everyCallExampleMatchesItsRuntimeArgumentValidationOutcome() {
        for (CapabilityTool<?> tool : tools) {
            validateExamples(tool.getName(), tool.getClass(), tool.getArgsClass());
        }
    }

    @Test
    void everyDeclaredRequiredParameterIsRejectedCentrallyWhenMissingOrNull() {
        for (CapabilityTool<?> tool : tools) {
            verifyRequiredParameters(tool.getName(), tool.getClass(), tool.getArgsClass());
        }
    }

    @Test
    void everyWorkspaceReadToolUsesTheTypedCapabilityBoundary() {
        for (NativeTool<?> tool : nativeTools) {
            ToolSecurity security = tool.getClass().getAnnotation(ToolSecurity.class);
            if (security != null && security.capability() == ToolCapability.WORKSPACE_READ) {
                assertTrue(
                        tool instanceof WorkspaceReadTool<?>,
                        () ->
                                tool.getName()
                                        + " declares WORKSPACE_READ without WorkspaceReadTool");
            }
        }
    }

    @Test
    void everyWorkspaceWriteToolUsesTheTypedCapabilityBoundary() {
        for (NativeTool<?> tool : nativeTools) {
            ToolSecurity security = tool.getClass().getAnnotation(ToolSecurity.class);
            if (security != null && security.capability() == ToolCapability.WORKSPACE_WRITE) {
                assertTrue(
                        tool instanceof WorkspaceWriteTool<?>,
                        () ->
                                tool.getName()
                                        + " declares WORKSPACE_WRITE without WorkspaceWriteTool");
            }
        }
    }

    private void validateExamples(
            @NonNull String toolName, @NonNull Class<?> toolClass, @NonNull Class<?> argsClass) {
        var examples = ToolDocs.examplesOf(toolClass);
        var results = ToolDocs.returnExamplesOf(toolClass);
        assertEquals(
                examples.size(), results.size(), "argument and result examples must be paired");
        for (int index = 0; index < examples.size(); index++) {
            var example = examples.get(index);
            var expectedResult = results.get(index);
            if (example == null || expectedResult == null)
                throw new AssertionError("Argument and result examples must exist");
            String legacyDiagnostic = legacyStaticFailureDiagnostic(toolName, expectedResult);
            if (!legacyDiagnostic.isEmpty()) {
                var failure =
                        assertThrows(
                                ToolExecutionException.class,
                                () ->
                                        NativeToolArgumentValidator.validate(
                                                toolName, mapper.readTree(example), argsClass));
                assertEquals(ToolErrorCode.VALIDATION.INVALID_ARGUMENTS, failure.errorCode());
                assertTrue(
                        String.valueOf(failure.getMessage())
                                .startsWith("Invalid arguments for " + toolName + ":"));
                assertTrue(
                        String.valueOf(failure.getMessage()).contains(legacyDiagnostic),
                        () ->
                                toolName
                                        + " must reject the exact bound in its paired invalid sample");
                continue;
            }
            if (expectedResult.startsWith("Invalid arguments for " + toolName + ":")) {
                var failure =
                        assertThrows(
                                ToolExecutionException.class,
                                () ->
                                        NativeToolArgumentValidator.validate(
                                                toolName, mapper.readTree(example), argsClass),
                                () ->
                                        toolName
                                                + " must reject its documented invalid example: "
                                                + example);
                assertEquals(ToolErrorCode.VALIDATION.INVALID_ARGUMENTS, failure.errorCode());
                assertTrue(
                        String.valueOf(failure.getMessage()).startsWith(expectedResult),
                        () ->
                                toolName
                                        + " validation diagnostic must match its paired result: "
                                        + expectedResult);
                continue;
            }
            assertDoesNotThrow(
                    () -> {
                        JsonNode args = mapper.readTree(example);
                        NativeToolArgumentValidator.validate(toolName, args, argsClass);
                        mapper.treeToValue(args, argsClass);
                    },
                    () -> toolName + " has an invalid call example: " + example);
        }
    }

    // These exact paired failures predate annotation preflight. Preserve their wire-visible
    // samples.
    private static @NonNull String legacyStaticFailureDiagnostic(
            @NonNull String toolName, @NonNull String result) {
        return switch (toolName) {
            case "read_github_repository" ->
                    result.equals("Invalid repository: the repository owner or name is invalid.")
                            ? "parameter 'repositoryOwner' does not match its required pattern"
                            : "";
            case "web_search" ->
                    result.equals("Invalid arguments: query must be at least 2 characters.")
                            ? "parameter 'query' is too short"
                            : "";
            case "read_sections" ->
                    result.equals("Invalid arguments: read between one and eight segment IDs.")
                            ? "parameter 'ids' has too many items"
                            : "";
            case "run_task" ->
                    result.equals(
                                    "Invalid arguments: exactly one command is required (background mode does not chain); got 2.")
                            ? "parameter 'commands' has too many items"
                            : "";
            case "find_sections" ->
                    result.equals(
                                    "Invalid arguments: use a non-blank keyword of at most 200 characters.")
                            ? "parameter 'query' must not be blank"
                            : "";
            case "web_fetch" ->
                    result.equals("Invalid arguments: url and objective must not be blank.")
                            ? "parameter 'objective' must not be blank"
                            : "";
            case "finish_read" ->
                    result.equals(
                                    "Invalid arguments: outcome must be complete, partial, or not_found. Correct all listed fields together. Choose supporting evidence and keep the answer within its scope; exact quotations are attached from evidenceIds. Combine related limitations.")
                            ? "parameter 'outcome' does not match its required pattern"
                            : "";
            default -> "";
        };
    }

    private void verifyRequiredParameters(
            @NonNull String toolName, @NonNull Class<?> toolClass, @NonNull Class<?> argsClass) {
        JsonNode schema = ToolSchemaCompiler.compileFromRecord(argsClass);
        ObjectNode complete = exampleArguments(toolClass, schema);
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
            ToolExecutionException missingFailure =
                    assertThrows(
                            ToolExecutionException.class,
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
            assertEquals(ToolErrorCode.VALIDATION.INVALID_ARGUMENTS, missingFailure.errorCode());

            ObjectNode explicitNull = complete.deepCopy();
            JsonNode nullParent = explicitNull.at(required.parentPointer());
            assertTrue(nullParent instanceof ObjectNode);
            ((ObjectNode) nullParent).putNull(required.name());
            ToolExecutionException nullFailure =
                    assertThrows(
                            ToolExecutionException.class,
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
                            .contains("'" + required.displayPath() + "'"),
                    () -> toolName + ": " + nullFailure.getMessage());
            assertEquals(ToolErrorCode.VALIDATION.INVALID_ARGUMENTS, nullFailure.errorCode());
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
            @NonNull Class<?> toolClass, @NonNull JsonNode schema) {
        List<String> examples = ToolDocs.examplesOf(toolClass);
        if (examples.isEmpty()) {
            return synthesizeObject(schema);
        }
        try {
            JsonNode parsed = mapper.readTree(String.valueOf(examples.get(0)));
            if (parsed instanceof ObjectNode object) {
                return object;
            }
            throw new AssertionError(toolClass.getName() + " has a non-object call example");
        } catch (Exception e) {
            throw new AssertionError(toolClass.getName() + " has an unreadable call example", e);
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
