package top.focess.veto.agent.loop;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.translation.VetoCapabilityTranslator;
import top.focess.veto.llm.core.*;
import top.focess.veto.llm.exceptions.ModelSchemaException;

class ResponseToolsTest {
    private final @org.jspecify.annotations.NonNull ObjectMapper mapper = new ObjectMapper();

    @Test
    void generationWithoutInputBindingsAcceptsOmittedInputs() throws Exception {
        var actions =
                mapper.readTree(
                        "[{\"id\":\"g\",\"label\":\"Greet\",\"type\":\"generate\",\"prompt\":\"Say hello\",\"outputs\":{\"answer\":\"message\"}},{\"id\":\"end\",\"label\":\"Finish\",\"type\":\"STOP\",\"result_binding\":\"answer\"}]");
        var call = new ToolCall("submit_guide", Map.of("actions", actions));
        var decoded =
                ResponseTools.decode(
                        new VetoResponse(null, List.of(call), null, null), call, mapper);
        assertTrue(decoded.guide() != null);
    }

    @Test
    void guidedCapabilityControlsItsNativeDefinition() {
        for (boolean enabled : List.of(false, true)) {
            var definitions =
                    ResponseTools.add(
                            List.of(), new VetoCapabilityTranslator().vetoResponseSchema(enabled));
            assertEquals(
                    enabled, definitions.stream().anyMatch(t -> t.name().equals("submit_guide")));
            assertTrue(
                    definitions.stream().anyMatch(t -> t.name().equals("answer_with_citations")));
        }
    }

    @Test
    void guideAndCitationSubmissionsKeepTheirStructuredFeatures() {
        var call =
                new ToolCall(
                        "submit_guide",
                        Map.of(
                                "actions",
                                List.of(Map.of("type", "STOP", "id", "end", "label", "Finish"))));
        var response = new VetoResponse(null, List.of(call), "Starting the program.", null);
        assertEquals(call, ResponseTools.submission(response));
        var decoded = ResponseTools.decode(response, call, mapper);
        var program = decoded.guide();
        if (program == null) throw new AssertionError("Missing guide");
        assertEquals("STOP", program.actions().get(0).get("type").asText());
        assertEquals("Starting the program.", decoded.message());
        assertNull(decoded.calls());
        var cited =
                new ToolCall(
                        "answer_with_citations",
                        Map.of(
                                "message",
                                "At [14:30](cite:time).",
                                "citations",
                                List.of(
                                        Map.of(
                                                "id",
                                                "time",
                                                "sources",
                                                List.of(
                                                        Map.of(
                                                                "message_index",
                                                                0,
                                                                "quote",
                                                                "14:30"))))));
        var answer =
                ResponseTools.decode(
                        new VetoResponse(null, List.of(cited), null, null), cited, mapper);
        var citations = answer.citations();
        if (citations == null) throw new AssertionError("Missing citations");
        assertEquals("time", citations.getFirst().id());
    }

    @Test
    void rejectsAmbiguousBatchesAndMalformedSubmissions() {
        var guide = new ToolCall("submit_guide", Map.of("actions", List.of()));
        assertThrows(
                top.focess.veto.agent.tool.ToolDocs.nonNullClass(ModelSchemaException.class),
                () ->
                        ResponseTools.submission(
                                new VetoResponse(
                                        null,
                                        List.of(guide, new ToolCall("read", Map.of())),
                                        null,
                                        null)));
        assertThrows(
                top.focess.veto.agent.tool.ToolDocs.nonNullClass(ModelSchemaException.class),
                () ->
                        ResponseTools.decode(
                                new VetoResponse(null, List.of(guide), null, null), guide, mapper));
    }
}
