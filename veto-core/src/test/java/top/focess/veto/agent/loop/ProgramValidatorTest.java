package top.focess.veto.agent.loop;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.mcp.ToolDocs;

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
}
