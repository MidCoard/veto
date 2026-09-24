package top.focess.veto.agent.loop;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.builtin.planning.ActionsProgramParser;
import top.focess.veto.builtin.planning.GenerateAction;
import top.focess.veto.builtin.planning.ProgramValidator;
import top.focess.veto.builtin.planning.Scope;

class ActionsProgramParserTest {
    private static final @NonNull ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void generationDefaultsToTextAndPreservesExplicitCitationMode() throws Exception {
        assertEquals(GenerateAction.ResponseMode.TEXT, generate("").responseMode());
        assertEquals(
                GenerateAction.ResponseMode.TEXT,
                generate(",\"response_mode\":\"TEXT\"").responseMode());
        assertEquals(
                GenerateAction.ResponseMode.CITATIONS,
                generate(",\"response_mode\":\"CITATIONS\"").responseMode());
        assertEquals(
                GenerateAction.ResponseMode.TEXT,
                new GenerateAction(
                                "draft",
                                "Draft",
                                "Write a summary.",
                                Map.of(),
                                Map.of(),
                                null,
                                null,
                                null)
                        .responseMode());
    }

    @Test
    void malformedResponseModesAreRejectedWithoutEchoingTheValue() {
        for (String value : List.of("null", "true", "1", "\"text\"", "\"private-value\"")) {
            var exception =
                    assertThrows(
                            ToolDocs.nonNullClass(ProgramValidator.InvalidProgramException.class),
                            () -> generate(",\"response_mode\":" + value));
            assertEquals("response_mode must be TEXT or CITATIONS", exception.getMessage());
        }
    }

    @Test
    void namedInputsRemainAvailableWithoutPromptPlaceholders() throws Exception {
        var generate = generate("");
        var scope = new Scope(MAPPER);
        var facts = Map.of("releaseDay", "Friday", "owners", List.of("Lin"));
        scope.put("source", facts);
        assertEquals("Summarize the supplied source.", generate.resolvePrompt(scope));
        assertEquals(Map.of("document", facts), generate.resolveInputs(scope));
        assertFalse(scope.contains("document"));
    }

    private @NonNull GenerateAction generate(@NonNull String modeField) throws Exception {
        var actions =
                MAPPER.readTree(
                        """
                [{"id":"draft","label":"Draft","type":"generate",
                  "prompt":"Summarize the supplied source.","inputs":{"document":"$source"},
                  "outputs":{"answer":"message"}%s},
                  {"id":"done","label":"Done","type":"STOP","result_binding":"answer"}]
                """
                                .formatted(modeField));
        return (GenerateAction) ActionsProgramParser.parse(actions).actions().getFirst();
    }
}
