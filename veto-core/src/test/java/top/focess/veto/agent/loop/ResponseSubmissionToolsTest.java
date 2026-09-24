package top.focess.veto.agent.loop;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.tool.*;
import top.focess.veto.agent.tool.ControlSubmissions;
import top.focess.veto.agent.tool.builtin.*;
import top.focess.veto.api.agent.tool.ControlSubmission;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.agent.tool.ToolExecutionException;
import top.focess.veto.builtin.planning.SubmitPlanTool;
import top.focess.veto.builtin.response.AnswerWithCitationsTool;

class ControlSubmissionToolsTest {
    private final @NonNull ObjectMapper mapper = new ObjectMapper();

    @Test
    void planToolIsAvailableWithoutSkills() {
        var tool = new SubmitPlanTool();
        var definition =
                AgentToolDefinition.from(
                        tool.getName(),
                        ToolDocs.nonNullClass(SubmitPlanTool.class),
                        tool.getArgsClass(),
                        tool.getCapability());
        assertEquals(
                List.of(definition), PromptCompiler.availableTools(List.of(definition), List.of()));
        assertEquals(ControlSubmission.Kind.EXECUTE, ControlSubmissions.kindOf(definition));
    }

    @Test
    void programSchemaChecksVariantsAndAllowsOmittedGenerationInputs() throws Exception {
        var type = ToolDocs.nonNullClass(SubmitPlanTool.Args.class);
        var example = ToolDocs.examplesOf(ToolDocs.nonNullClass(SubmitPlanTool.class)).getFirst();
        NativeToolArgumentValidator.validate("submit_plan", mapper.readTree(example), type);
        assertThrows(
                ToolDocs.nonNullClass(ToolExecutionException.class),
                () ->
                        NativeToolArgumentValidator.validate(
                                "submit_plan",
                                mapper.readTree(
                                        example.replace(
                                                "\"answer\":\"message\"", "\"answer\":\"false\"")),
                                type));
        for (String bad :
                List.of(
                        "{\"actions\":[]}",
                        "{\"actions\":[{\"id\":\"x\",\"label\":\"x\",\"type\":\"unknown\"}]}"))
            assertThrows(
                    ToolDocs.nonNullClass(ToolExecutionException.class),
                    () ->
                            NativeToolArgumentValidator.validate(
                                    "submit_plan", mapper.readTree(bad), type));
    }

    @Test
    void citedAnswerRequiresSourcesAndConstrainedIds() throws Exception {
        var type = ToolDocs.nonNullClass(AnswerWithCitationsTool.Args.class);
        var example =
                ToolDocs.examplesOf(ToolDocs.nonNullClass(AnswerWithCitationsTool.class))
                        .getFirst();
        NativeToolArgumentValidator.validate(
                "answer_with_citations", mapper.readTree(example), type);
        for (String bad :
                List.of(
                        "{\"message\":\"Answer\",\"citations\":[]}",
                        "{\"message\":\"[source](cite:meeting)\",\"citations\":[{\"id\":\"meeting\",\"sources\":{\"quote\":\"The meeting starts at 14:30.\"}}]}",
                        example.replace("\"meeting\"", "\"bad id\"")))
            assertThrows(
                    ToolDocs.nonNullClass(ToolExecutionException.class),
                    () ->
                            NativeToolArgumentValidator.validate(
                                    "answer_with_citations", mapper.readTree(bad), type));
    }

    @Test
    void submissionsRequireAuthorizedToolExecution() {
        ToolCallContextHolder.clear();
        assertThrows(SecurityException.class, ToolCallContextHolder::control);
        assertThrows(
                SecurityException.class,
                () -> new SubmitPlanTool().execute(new SubmitPlanTool.Args(List.of())));
        assertThrows(
                SecurityException.class,
                () ->
                        new AnswerWithCitationsTool()
                                .execute(new AnswerWithCitationsTool.Args("Answer", List.of())));
    }
}
