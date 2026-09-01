package top.focess.veto.agent.loop;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.mcp.ToolDocs;
import top.focess.veto.llm.core.ToolCall;
import top.focess.veto.llm.core.VetoResponse;
import top.focess.veto.llm.exceptions.ModelSchemaException;

class ResponseEnforcerTest {

    @Test
    void rejectsResponseFieldMasqueradingAsToolName() {
        VetoResponse response =
                new VetoResponse(
                        null,
                        List.of(new ToolCall("message", Map.of("message", "What should I do?"))),
                        null,
                        new VetoResponse.Features(false),
                        null);

        ModelSchemaException error =
                assertThrows(
                        ToolDocs.nonNullClass(ModelSchemaException.class),
                        () -> ResponseEnforcer.enforce(response, false, Set.of("think")));
        String message = error.getMessage();
        assertTrue(message != null && message.contains("is not in this turn's tool catalog"));
    }

    @Test
    void guidedAuthoringCannotSilentlySwitchBackBeforeProgramLoads() throws Exception {
        var actions =
                new ObjectMapper()
                        .readTree(
                                """
                                [{"id":"done","label":"Finish","type":"STOP"}]
                                """);
        var response =
                new VetoResponse(null, List.of(), null, new VetoResponse.Features(false), actions);

        assertThrows(
                ToolDocs.nonNullClass(ModelSchemaException.class),
                () -> ResponseEnforcer.enforce(response, true));
    }
}
