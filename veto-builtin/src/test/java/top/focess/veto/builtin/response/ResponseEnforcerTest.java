package top.focess.veto.builtin.response;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.agent.control.ControlHost;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.agent.tool.ToolExecutionException;
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

    @Test
    void malformedAnswerIsRejectedBeforeAmbiguousSourcesAreInspected() {
        @NonNull ControlHost host = mock();
        var tool = new AnswerWithCitationsTool(host);
        var args =
                new AnswerWithCitationsTool.Args(
                        "Launch Friday.",
                        List.of(
                                new AnswerWithCitationsTool.Citation(
                                        "source",
                                        List.of(
                                                new AnswerWithCitationsTool.Source(
                                                        null, "Launch Friday.")))));
        var error =
                assertThrows(
                        ToolDocs.nonNullClass(ToolExecutionException.class),
                        () -> tool.execute(args));
        assertTrue(error.content().contains("ordinary text"));
        assertFalse(error.content().contains("matches several"));
        verifyNoInteractions(host);
        assertThrows(
                ToolDocs.nonNullClass(ToolExecutionException.class),
                () -> tool.execute(new AnswerWithCitationsTool.Args("Launch Friday.", List.of())));
        verifyNoInteractions(host);
    }
}
