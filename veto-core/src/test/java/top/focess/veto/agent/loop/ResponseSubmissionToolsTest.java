package top.focess.veto.agent.loop;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.capability.LoopControlCapabilityImpl;
import top.focess.veto.agent.tool.*;
import top.focess.veto.agent.tool.builtin.*;

class ResponseSubmissionToolsTest {
    private final @NonNull ObjectMapper mapper = new ObjectMapper();

    @Test
    void guideAvailabilityFollowsSessionCapability() {
        var tool = new SubmitPlanTool(new LoopControlCapabilityImpl());
        var definition =
                AgentToolDefinition.from(
                        tool.getName(),
                        ToolDocs.nonNullClass(SubmitPlanTool.class),
                        tool.getArgsClass(),
                        tool.getCapability());
        assertTrue(PromptCompiler.availableTools(List.of(definition), true, false).isEmpty());
        assertEquals(
                List.of(definition),
                PromptCompiler.availableTools(List.of(definition), true, true));
        assertEquals(ResponseSubmission.Kind.PLAN, ResponseSubmission.Metadata.kindOf(definition));
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
        var capability = new LoopControlCapabilityImpl();
        assertThrows(
                ToolDocs.nonNullClass(SecurityException.class),
                () -> capability.submitPlan(mapper.createArrayNode()));
        assertThrows(
                ToolDocs.nonNullClass(SecurityException.class),
                () ->
                        capability.answerWithCitations(
                                new ResponseRequest.Answer("Answer", List.of())));
    }
}
