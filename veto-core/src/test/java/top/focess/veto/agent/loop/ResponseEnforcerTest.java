package top.focess.veto.agent.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.VetoResponse;
import top.focess.veto.api.llm.exceptions.ModelSchemaException;

class ResponseEnforcerTest {
    @Test
    void handwrittenCitationLinksRequireToolMetadataButCodeExamplesRemainText() {
        assertThrows(
                ToolDocs.nonNullClass(ModelSchemaException.class),
                () ->
                        ResponseEnforcer.enforce(
                                new VetoResponse(null, null, "Launch [Friday](cite:launch)")));
        for (String text :
                List.of(
                        "Example: `[Friday](cite:launch)`",
                        "Example: `[citation:launch]`",
                        "```markdown\n[Friday](cite:launch)\n```",
                        "[External](https://example.com)"))
            assertDoesNotThrow(() -> ResponseEnforcer.enforce(new VetoResponse(null, null, text)));
    }

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
                                        null, null, "[Original](cite:source)", citations)));
        for (String message :
                List.of(
                        "Original [citation:source]",
                        "Original",
                        "[Original](cite:other)",
                        "Example: `[Original](cite:source)`",
                        "](cite:source)",
                        "![Original](cite:source)",
                        "\\[Original](cite:source)")) {
            try {
                ResponseEnforcer.enforce(new VetoResponse(null, null, message, citations));
                fail("Declared source must have a corresponding clickable link");
            } catch (ModelSchemaException error) {
                assertTrue(String.valueOf(error.getMessage()).contains("[label](cite:"));
            }
        }
        assertDoesNotThrow(
                () ->
                        ResponseEnforcer.enforce(
                                new VetoResponse(null, null, "> Unattributed formatting")));
        try {
            ResponseEnforcer.enforce(new VetoResponse(null, null, "Original [citation:source]"));
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
                        null);

        ModelSchemaException error =
                assertThrows(
                        ToolDocs.nonNullClass(ModelSchemaException.class),
                        () -> ResponseEnforcer.enforce(response, Set.of("think")));
        String message = error.getMessage();
        assertTrue(message != null && message.contains("is not in this turn's tool catalog"));
    }
}
