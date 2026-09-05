package top.focess.veto.agent.loop;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.identity.SystemPromptResolver;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.agent.tool.ToolResult;
import top.focess.veto.util.Nullness;

class GuidedBindingsTest {
    @Test
    void typedInputsAliasesAndOutputsPreserveData() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Scope scope = new Scope(mapper);
        scope.put("arg", "two words");
        var program =
                ActionsProgramParser.parse(
                        mapper.readTree(
                                """
            [{"id":"run","label":"Run","type":"tool","tool":"run_command",
              "inputs":{"commands":[{"executable":"java","args":["$arg","$$HOME"]}],"timeout":20,"network":false},"outputs":{}},
             {"id":"stop","label":"Stop","type":"STOP"}]
            """));
        Map<String, Object> resolved = program.actions().getFirst().resolveInputs(scope);
        assertEquals(20, resolved.get("timeout"));
        assertEquals(false, resolved.get("network"));
        assertEquals(
                List.of(Map.of("executable", "java", "args", List.of("two words", "$HOME"))),
                resolved.get("commands"));
        scope.bindTool(
                Map.of("items", "matches", "ok", "success"),
                new ToolResult("find_files", "id", true, "{\"matches\":[\"$literal\",\"b\"]}"));
        assertEquals(List.of("$literal", "b"), scope.get("items"));
        assertEquals(true, scope.get("ok"));
        var generate =
                new GenerateAction(
                        "g",
                        "Generate",
                        "$alias and $arg",
                        Map.of("alias", "$items"),
                        Map.of(),
                        false,
                        null,
                        0.2);
        assertEquals("[$literal, b] and two words", generate.resolvePrompt(scope));
        assertFalse(scope.contains("alias"));
        assertThrows(
                ToolDocs.nonNullClass(IllegalArgumentException.class),
                () -> scope.resolveValue("$missing"));
    }

    @Test
    void conditionalLoopsAreBoundedButUnconditionalCyclesAreRejected() {
        var stop = new StopAction("stop", "Stop", null);
        var jump = new GotoAction("jump", "Jump", 0);
        var tool = new ToolAction("tool", "Tool", "think", Map.of(), Map.of());
        assertThrows(
                ToolDocs.nonNullClass(ProgramValidator.InvalidProgramException.class),
                () -> ProgramValidator.validate(new ActionsProgram(List.of(tool, jump, stop))));
        var condition =
                new ConditionalGotoAction(
                        "check", "Check", new Check.Numeric("CURRENT_STEPS", "lt", "4"), 0, 1);
        assertDoesNotThrow(
                () -> ProgramValidator.validate(new ActionsProgram(List.of(condition, stop))));
        Scope scope = new Scope(new ObjectMapper());
        assertTrue(CheckEvaluator.evaluate(condition.check(), scope, 3));
        assertFalse(CheckEvaluator.evaluate(condition.check(), scope, 4));
        assertThrows(
                ToolDocs.nonNullClass(ProgramValidator.InvalidProgramException.class),
                () ->
                        ProgramValidator.validate(
                                new ActionsProgram(
                                        List.of(
                                                new ConditionalGotoAction(
                                                        "spin", "Spin", new Check.Empty("x"), 0, 0),
                                                stop))));
    }

    @Test
    void malformedIndicesAndMissingFieldsFailClosed() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var input =
                mapper.readTree(
                        "[{\"id\":\"jump\",\"label\":\"Jump\",\"type\":\"goto\",\"index\":\"0\"}]");
        assertThrows(
                ToolDocs.nonNullClass(ProgramValidator.InvalidProgramException.class),
                () -> ActionsProgramParser.parse(input));
        Scope scope = new Scope(mapper);
        assertThrows(
                ToolDocs.nonNullClass(IllegalArgumentException.class),
                () -> scope.bindTool(Map.of("x", "absent"), new ToolResult("t", "id", true, "{}")));
    }

    @Test
    void fewShotProgramsParseAndValidate() throws Exception {
        String prompt = new SystemPromptResolver().defaultPrompt();
        var matcher = Pattern.compile("```json\\s*([\\s\\S]*?)```").matcher(prompt);
        ObjectMapper mapper = new ObjectMapper();
        int count = 0;
        while (matcher.find()) {
            var json = mapper.readTree(Nullness.requireNonNull(matcher.group(1)));
            if (json.has("actions")) {
                ProgramValidator.validate(ActionsProgramParser.parse(json.path("actions")));
                count++;
            }
        }
        assertTrue(count >= 5);
    }
}
