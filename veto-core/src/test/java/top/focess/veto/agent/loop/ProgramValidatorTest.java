package top.focess.veto.agent.loop;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.agent.workflow.ActionsProgram;
import top.focess.veto.api.agent.workflow.Check;
import top.focess.veto.api.agent.workflow.ConditionalGotoAction;
import top.focess.veto.api.agent.workflow.GenerateAction;
import top.focess.veto.api.agent.workflow.Scope;
import top.focess.veto.api.agent.workflow.StopAction;
import top.focess.veto.builtin.planning.ActionsProgramParser;
import top.focess.veto.builtin.planning.ProgramValidator;
import top.focess.veto.util.Nullness;

class ProgramValidatorTest {

    @Test
    void acceptsProgramWithIdentifiedLabelledStop() {
        ActionsProgram program =
                new ActionsProgram(List.of(new StopAction("finish", "Return the result", null)));

        assertDoesNotThrow(() -> ProgramValidator.validate(program));
    }

    @Test
    void rejectsBlankActionId() {
        ActionsProgram program =
                new ActionsProgram(List.of(new StopAction(" ", "Return the result", null)));

        ProgramValidator.InvalidProgramException error =
                assertThrows(
                        ToolDocs.nonNullClass(ProgramValidator.InvalidProgramException.class),
                        () -> ProgramValidator.validate(program));

        assertEquals("action id must not be blank at index 0", error.getMessage());
    }

    @Test
    void rejectsBlankActionLabel() {
        ActionsProgram program = new ActionsProgram(List.of(new StopAction("finish", " ", null)));

        ProgramValidator.InvalidProgramException error =
                assertThrows(
                        ToolDocs.nonNullClass(ProgramValidator.InvalidProgramException.class),
                        () -> ProgramValidator.validate(program));

        assertEquals("action finish: label must not be blank", error.getMessage());
    }

    @Test
    void rejectsBindingMissingOnOneBranchBeforeExecutingAnyStep() {
        var program =
                new ActionsProgram(
                        List.of(
                                new ConditionalGotoAction(
                                        "branch", "Branch", new Check.Empty("optional"), 1, 2),
                                new GenerateAction(
                                        "gen",
                                        "Generate",
                                        "answer",
                                        Map.of(),
                                        Map.of("answer", "message"),
                                        null,
                                        null,
                                        null),
                                new StopAction("stop", "Stop", "answer")));
        assertThrows(
                ToolDocs.nonNullClass(ProgramValidator.InvalidProgramException.class),
                () -> ProgramValidator.validateInputs(program));
    }

    @Test
    void permitsOptionalChecksAndEscapedLiteralInputs() {
        var program =
                new ActionsProgram(
                        List.of(
                                new ConditionalGotoAction(
                                        "branch", "Branch", new Check.Empty("optional"), 1, 1),
                                new GenerateAction(
                                        "gen",
                                        "Generate",
                                        "Use $literal and $$escaped",
                                        Map.of("literal", "$$literal"),
                                        Map.of("answer", "message"),
                                        null,
                                        null,
                                        null),
                                new StopAction("stop", "Stop", "answer")));
        assertDoesNotThrow(() -> ProgramValidator.validateInputs(program));
    }

    @ParameterizedTest
    @ValueSource(strings = {"answer", "$answer"})
    void stopResultReferenceUsesSameBindingAtValidationAndExecution(@NonNull String reference)
            throws Exception {
        var mapper = new ObjectMapper();
        var program =
                ActionsProgramParser.parse(
                        mapper.readTree(
                                """
                [{"id":"write","label":"Write answer","type":"generate",
                  "prompt":"Write a greeting","outputs":{"answer":"message"}},
                 {"id":"finish","label":"Return answer","type":"STOP","result_binding":"%s"}]
                """
                                        .formatted(reference)));

        assertDoesNotThrow(() -> ProgramValidator.validate(program));
        assertDoesNotThrow(() -> ProgramValidator.validateInputs(program));
        var stop = (StopAction) program.actions().getLast();
        assertEquals("answer", stop.resultBinding());
        Scope scope = new Scope(mapper);
        scope.put("answer", "Hello.");
        assertEquals(
                "Hello.", scope.opt(Nullness.requireNonNull(stop.resultBinding())).orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"answer", "$answer"})
    void bothStopReferenceFormsRejectAnUnboundResult(@NonNull String reference) {
        var program =
                new ActionsProgram(List.of(new StopAction("finish", "Return answer", reference)));
        assertThrows(
                ToolDocs.nonNullClass(ProgramValidator.InvalidProgramException.class),
                () -> ProgramValidator.validateInputs(program));
    }
}
