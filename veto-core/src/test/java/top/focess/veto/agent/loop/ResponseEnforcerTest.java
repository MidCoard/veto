package top.focess.veto.agent.loop;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.mcp.ToolDocs;
import top.focess.veto.llm.core.VetoResponse;
import top.focess.veto.llm.exceptions.ModelSchemaException;

class ResponseEnforcerTest {

    @Test
    void guidedAuthoringCannotSilentlySwitchBackBeforeProgramLoads() throws Exception {
        var actions =
                new ObjectMapper()
                        .readTree(
                                """
                                [{"id":"done","label":"Finish","type":"STOP"}]
                                """);
        var response =
                new VetoResponse(
                        null,
                        List.of(),
                        null,
                        new VetoResponse.Features(false),
                        actions);

        assertThrows(
                ToolDocs.nonNullClass(ModelSchemaException.class),
                () -> ResponseEnforcer.enforce(response, true));
    }
}
