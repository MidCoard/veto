package top.focess.veto.agent.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.llm.core.ToolCall;
import top.focess.veto.llm.core.VetoResponse;
import top.focess.veto.llm.exceptions.ModelSchemaException;

class ResponseEnforcerTest {
    @Test
    void citationDeclarationsRequireClickableLinks() {
        var citations =
                List.of(
                        new VetoResponse.Citation(
                                "source", List.of(new VetoResponse.Source(0, "Original"))));
        assertDoesNotThrow(
                () ->
                        ResponseEnforcer.enforce(
                                new VetoResponse(
                                        null, null, "[Original](cite:source)", null, citations),
                                false));
        for (String message :
                List.of("Original [citation:source]", "Original", "[Original](cite:other)")) {
            try {
                ResponseEnforcer.enforce(
                        new VetoResponse(null, null, message, null, citations), false);
                fail("Declared source must have a corresponding clickable link");
            } catch (ModelSchemaException error) {
                assertTrue(String.valueOf(error.getMessage()).contains("[label](cite:"));
            }
        }
        assertDoesNotThrow(
                () ->
                        ResponseEnforcer.enforce(
                                new VetoResponse(null, null, "> Unattributed formatting", null),
                                false));
        try {
            ResponseEnforcer.enforce(
                    new VetoResponse(null, null, "Original [citation:source]", null), false);
            fail("A bare citation marker must not silently bypass source declarations");
        } catch (ModelSchemaException error) {
            assertTrue(String.valueOf(error.getMessage()).contains("message_index"));
        }
    }

    @Test
    void rejectsResponseFieldMasqueradingAsToolName() {
        VetoResponse response =
                new VetoResponse(
                        null,
                        List.of(new ToolCall("message", Map.of("message", "What should I do?"))),
                        null,
                        null);

        ModelSchemaException error =
                assertThrows(
                        ToolDocs.nonNullClass(ModelSchemaException.class),
                        () -> ResponseEnforcer.enforce(response, false, Set.of("think")));
        String message = error.getMessage();
        assertTrue(message != null && message.contains("is not in this turn's tool catalog"));
    }

    @Test
    void guideRunsOnlyWhenEnabledAndCannotMixWithCalls() throws Exception {
        var actions =
                new ObjectMapper()
                        .readTree("[{\"id\":\"done\",\"label\":\"Finish\",\"type\":\"STOP\"}]");
        var guide = new VetoResponse.Guide(actions);
        var response = new VetoResponse(null, null, null, guide);
        assertDoesNotThrow(() -> ResponseEnforcer.enforce(response, true));
        assertThrows(
                ToolDocs.nonNullClass(ModelSchemaException.class),
                () -> ResponseEnforcer.enforce(response, false));
        var mixed = new VetoResponse(null, List.of(new ToolCall("think", Map.of())), null, guide);
        assertThrows(
                ToolDocs.nonNullClass(ModelSchemaException.class),
                () -> ResponseEnforcer.enforce(mixed, true));
        assertDoesNotThrow(
                () -> ResponseEnforcer.enforce(new VetoResponse(null, null, "answer", null), true));
        var empty =
                new VetoResponse(
                        null,
                        null,
                        null,
                        new VetoResponse.Guide(new ObjectMapper().createArrayNode()));
        assertThrows(
                ToolDocs.nonNullClass(ModelSchemaException.class),
                () -> ResponseEnforcer.enforce(empty, true));
    }

    @Test
    void guideActionsMustBeAnArray() throws Exception {
        for (String value : List.of("null", "{}", "true", "42", "\"STOP\"")) {
            var response =
                    new VetoResponse(
                            null,
                            null,
                            null,
                            new VetoResponse.Guide(new ObjectMapper().readTree(value)));
            assertThrows(
                    ToolDocs.nonNullClass(ModelSchemaException.class),
                    () -> ResponseEnforcer.enforce(response, true),
                    value);
        }
    }
}
