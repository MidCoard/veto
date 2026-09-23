package top.focess.veto.agent.intercept;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.drift.ReadHistory;
import top.focess.veto.agent.screening.*;
import top.focess.veto.agent.tool.*;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.agent.tool.ToolExecutionException;
import top.focess.veto.builtin.planning.ActionsProgramParser;

class GatewayRemotePlanValidationTest {
    private static final @NonNull ObjectMapper MAPPER = new ObjectMapper();

    private void validate(@NonNull String schema, @NonNull String inputs) throws Exception {
        var definition =
                new RemoteToolDefinition(
                        "remote_fixture", "Fixture", "remote-server", MAPPER.readTree(schema));
        ToolEngine engine = mock(ToolDocs.nonNullClass(ToolEngine.class));
        when(engine.resolveDefinition("remote_fixture")).thenReturn(definition);
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
                [{"id":"step","label":"Run","type":"tool","tool":"remote_fixture","inputs":%s,"outputs":{}},
                 {"id":"done","label":"Done","type":"STOP"}]
                """
                                        .formatted(inputs)));
        gateway.validateProgram(program, engine, Set.of("remote_fixture"), MAPPER);
        verify(engine, only()).resolveDefinition("remote_fixture");
    }

    @Test
    void remoteReferencesDoNotHideRequiredFieldsOrWrongLiterals() throws Exception {
        String schema =
                """
                {"type":"object","properties":{"source":{"type":"string"},"limit":{"type":"integer","minimum":1}},
                 "required":["source","limit"],"additionalProperties":false}
                """;
        assertDoesNotThrow(() -> validate(schema, "{\"source\":\"$source\",\"limit\":1}"));
        assertThrows(
                ToolDocs.nonNullClass(ToolExecutionException.class),
                () -> validate(schema, "{\"source\":\"$source\"}"));
        assertThrows(
                ToolDocs.nonNullClass(ToolExecutionException.class),
                () -> validate(schema, "{\"source\":\"$source\",\"limit\":false}"));
        assertThrows(
                ToolDocs.nonNullClass(ToolExecutionException.class),
                () -> validate(schema, "{\"source\":\"$source\",\"limit\":1,\"unknown\":true}"));
    }

    @Test
    void remoteEscapedLiteralCannotBypassReferencedEnumWithOrWithoutBindings() throws Exception {
        String schema =
                """
                {"$defs":{"home":{"type":"string","enum":["$HOME"]}},"type":"object",
                 "properties":{"home":{"$ref":"#/$defs/home"},"source":{"type":"string"}},
                 "required":["home"],"additionalProperties":false}
                """;
        assertDoesNotThrow(() -> validate(schema, "{\"home\":\"$$HOME\"}"));
        assertDoesNotThrow(() -> validate(schema, "{\"home\":\"$$HOME\",\"source\":\"$source\"}"));
        assertThrows(
                ToolDocs.nonNullClass(ToolExecutionException.class),
                () -> validate(schema, "{\"home\":\"$$OTHER\"}"));
        assertThrows(
                ToolDocs.nonNullClass(ToolExecutionException.class),
                () -> validate(schema, "{\"home\":\"$$OTHER\",\"source\":\"$source\"}"));
        assertThrows(
                ToolDocs.nonNullClass(ToolExecutionException.class),
                () -> validate(schema, "{\"home\":\"$$$HOME\"}"));
    }

    @Test
    void remoteOpenDictionaryRetainsItsValueSchema() throws Exception {
        String schema =
                """
                {"type":"object","additionalProperties":{"type":"integer","minimum":0}}
                """;
        assertDoesNotThrow(() -> validate(schema, "{\"known\":1,\"later\":\"$count\"}"));
        assertThrows(
                ToolDocs.nonNullClass(ToolExecutionException.class),
                () -> validate(schema, "{\"known\":-1,\"later\":\"$count\"}"));
    }
}
