package top.focess.veto.agent.intercept;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.screening.*;
import top.focess.veto.agent.tool.*;
import top.focess.veto.api.agent.control.ControlHost;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.agent.tool.ToolExecutionException;
import top.focess.veto.api.llm.ToolDefinition;
import top.focess.veto.builtin.planning.ActionsProgramParser;
import top.focess.veto.builtin.planning.PlanPreflight;

class GatewayRemotePlanValidationTest {
    private static final @NonNull ObjectMapper MAPPER = new ObjectMapper();

    private void validate(@NonNull String schema, @NonNull String inputs) throws Exception {
        var definition =
                new RemoteToolDefinition(
                        "remote_fixture", "Fixture", "remote-server", MAPPER.readTree(schema));
        @NonNull ControlHost host = mock();
        @NonNull ToolDefinition advertised = mock();
        when(advertised.name()).thenReturn("remote_fixture");
        when(host.tools()).thenReturn(List.of(new ControlHost.Tool(advertised, null, null, null)));
        doAnswer(
                        call -> {
                            NativeToolArgumentValidator.validateAgainstSchema(
                                    "remote_fixture",
                                    call.getArgument(1),
                                    definition.inputSchema(),
                                    call.getArgument(2));
                            return null;
                        })
                .when(host)
                .validateInputs(eq("remote_fixture"), any(), any());
        var program =
                ActionsProgramParser.parse(
                        MAPPER.readTree(
                                """
                [{"id":"step","label":"Run","type":"tool","tool":"remote_fixture","inputs":%s,"outputs":{}},
                 {"id":"done","label":"Done","type":"STOP"}]
                """
                                        .formatted(inputs)));
        PlanPreflight.validate(program, host, MAPPER);
        verify(host).validateInputs(eq("remote_fixture"), any(), any());
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

    @Test
    void remoteInvalidReferencesFailClosed() {
        assertThrows(
                ToolDocs.nonNullClass(ToolExecutionException.class),
                () ->
                        validate(
                                "{\"type\":\"object\",\"properties\":{\"value\":{\"$ref\":\"#/$defs/missing\"}}}",
                                "{\"value\":\"anything\"}"));
        assertThrows(
                ToolDocs.nonNullClass(ToolExecutionException.class),
                () ->
                        validate(
                                "{\"type\":\"object\",\"properties\":{\"value\":{\"$ref\":\"https://example.com/schema\"}}}",
                                "{\"value\":\"anything\"}"));
        assertThrows(
                ToolDocs.nonNullClass(ToolExecutionException.class),
                () ->
                        validate(
                                "{\"$defs\":{\"loop\":{\"$ref\":\"#/$defs/loop\"}},\"type\":\"object\",\"properties\":{\"value\":{\"$ref\":\"#/$defs/loop\"}}}",
                                "{\"value\":\"anything\"}"));
    }
}
