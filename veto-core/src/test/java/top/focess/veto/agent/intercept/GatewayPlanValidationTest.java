package top.focess.veto.agent.intercept;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.drift.ReadHistory;
import top.focess.veto.agent.loop.ActionsProgramParser;
import top.focess.veto.agent.screening.*;
import top.focess.veto.agent.tool.*;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.api.agent.screening.Danger;
import top.focess.veto.api.agent.tool.Required;
import top.focess.veto.api.agent.tool.RequiredWhen;
import top.focess.veto.api.agent.tool.StringConstraint;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolDocs;

class GatewayPlanValidationTest {
    private static final @NonNull ObjectMapper MAPPER = new ObjectMapper();

    private record Item(@NonNull String name, @Required int count) {}

    private record Args(
            @NonNull String source,
            @Required boolean enabled,
            List<Item> items,
            @StringConstraint(pattern = "^\\$HOME$") String home,
            String mode,
            @RequiredWhen(field = "mode", values = "WRITE", rejectBlank = true) String content) {}

    private void validate(@NonNull String inputs) throws Exception {
        var definition =
                new NativeToolDefinition(
                        "fixture",
                        "Validation fixture",
                        ToolCapability.WORKSPACE_READ,
                        Danger.SAFE,
                        false,
                        ToolDocs.nonNullClass(GatewayPlanValidationTest.class),
                        ToolDocs.nonNullClass(Args.class),
                        Map.of());
        ToolEngine engine = mock(ToolDocs.nonNullClass(ToolEngine.class));
        when(engine.resolveDefinition("fixture")).thenReturn(definition);
        Gateway gateway =
                new Gateway(
                        mock(ToolDocs.nonNullClass(Workspace.class)),
                        new DangerComputation(),
                        SlmScreeningProvider.unavailable(),
                        DeployerPolicy.FULL_ACCESS,
                        ProtectedSet.empty(),
                        new ReadHistory());
        var program =
                ActionsProgramParser.parse(
                        MAPPER.readTree(
                                """
                [{"id":"step","label":"Read","type":"tool","tool":"fixture","inputs":%s,"outputs":{}},
                 {"id":"done","label":"Done","type":"STOP"}]
                """
                                        .formatted(inputs)));
        gateway.validateProgram(program, engine, Set.of("fixture"), MAPPER);
        verify(engine, only()).resolveDefinition("fixture");
    }

    private void rejects(@NonNull String inputs, @NonNull String diagnostic) {
        var error =
                assertThrows(
                        ToolDocs.nonNullClass(ToolExecutionException.class),
                        () -> validate(inputs));
        assertTrue(error.content().contains(diagnostic), error.content());
    }

    @Test
    void referenceDoesNotHideMissingRequiredSibling() {
        rejects(
                """
                {"source":"$document"}
                """,
                "enabled");
    }

    @Test
    void referenceDoesNotHideUnknownSibling() {
        rejects(
                """
                {"source":"$document","enabled":true,"extra":1}
                """,
                "extra");
    }

    @Test
    void referenceDoesNotHideWrongLiteralSiblingType() {
        rejects(
                """
                {"source":"$document","enabled":"true"}
                """,
                "enabled");
    }

    @Test
    void referenceDoesNotHideLiteralDiscriminatorConditionalRequirements() {
        rejects(
                """
                {"source":"$document","enabled":true,"mode":"WRITE"}
                """,
                "content");
        rejects(
                """
                {"source":"$document","enabled":true,"mode":"WRITE","content":"  "}
                """,
                "content");
        assertDoesNotThrow(
                () ->
                        validate(
                                """
                {"source":"$document","enabled":true,"mode":"WRITE","content":"$body"}
                """));
        assertDoesNotThrow(
                () ->
                        validate(
                                """
                {"source":"$document","enabled":true,"mode":"READ"}
                """));
    }

    @Test
    void defersOnlyReferencedArrayElementsAndNestedValues() {
        assertDoesNotThrow(
                () ->
                        validate(
                                """
                {"source":"$document","enabled":true,
                 "items":["$item",{"name":"Known item","count":"$count"}]}
                """));
        rejects(
                """
                {"source":"$document","enabled":true,
                 "items":["$item",{"name":"Known item","count":false}]}
                """,
                "items[1].count");
        rejects(
                """
                {"source":"$document","enabled":true,
                 "items":[{"name":"$name"}]}
                """,
                "items[0].count");
    }

    @Test
    void escapedDollarIsValidatedAsLiteralAfterOneUnescape() {
        assertDoesNotThrow(
                () ->
                        validate(
                                """
                {"source":"$document","enabled":true,"home":"$$HOME"}
                """));
        rejects(
                """
                {"source":"$document","enabled":true,"home":"$$OTHER"}
                """,
                "home");
        rejects(
                """
                {"source":"$document","enabled":"$$flag"}
                """,
                "enabled");
    }

    @Test
    void literalPlanRetainsExistingValidationAndEscaping() {
        assertDoesNotThrow(
                () ->
                        validate(
                                """
                {"source":"known source","enabled":true,"home":"$$HOME"}
                """));
        rejects(
                """
                {"source":"known source","enabled":"true"}
                """,
                "enabled");
    }
}
