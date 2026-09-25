package top.focess.veto.agent.tool;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.agent.screening.Danger;
import top.focess.veto.api.agent.tool.Doc;
import top.focess.veto.api.agent.tool.NativeTool;
import top.focess.veto.api.agent.tool.ParamCategory;
import top.focess.veto.api.agent.tool.SecurityHint;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolDoc;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.agent.tool.ToolResultFormat;
import top.focess.veto.api.agent.tool.ToolSecurity;
import top.focess.veto.builtin.questions.QuestionRuntime;
import top.focess.veto.builtin.tools.AskUserTool;
import top.focess.veto.builtin.tools.InputTaskTool;
import top.focess.veto.builtin.tools.StopTaskTool;

class ToolContractValidatorTest {
    @Test
    void acceptsTypedUserInteractionNativeTool() {
        @NonNull QuestionRuntime runtime =
                org.mockito.Mockito.mock(ToolDocs.nonNullClass(QuestionRuntime.class));
        var tool = new AskUserTool(runtime);
        assertDoesNotThrow(
                () ->
                        ToolContractValidator.validateHandler(
                                tool, ToolSchemaCompiler.compileNative(tool)));
    }

    @Test
    void acceptsProcessInputWithoutShellCommand() {
        var tool = new InputTaskTool();
        assertDoesNotThrow(
                () ->
                        ToolContractValidator.validateHandler(
                                tool, ToolSchemaCompiler.compileNative(tool)));
    }

    @Test
    void acceptsPluginLocalNativeControlTool() {
        var tool = new StopTaskTool();
        assertDoesNotThrow(
                () ->
                        ToolContractValidator.validateHandler(
                                tool, ToolSchemaCompiler.compileNative(tool)));
    }

    @Test
    void processInputStillRejectsFilesystemPathAuthority() {
        var tool = new InvalidProcessInputTool();
        var failure =
                assertThrows(
                        ToolDocs.nonNullClass(IllegalArgumentException.class),
                        () ->
                                ToolContractValidator.validateHandler(
                                        tool, ToolSchemaCompiler.compileNative(tool)));
        assertTrue(String.valueOf(failure.getMessage()).contains("not a FILESYSTEM_PATH argument"));
    }

    @ToolSecurity(capability = ToolCapability.PROCESS_EXECUTION, defaultDanger = Danger.SAFE)
    @ToolDoc(
            description = "Invalid mixed process authority fixture.",
            behavior = "Returns a marker.",
            whenToUse = "Contract validation tests.",
            whenNotToUse = "Production execution.",
            resultContract = "Returns a marker.",
            errorsAndEdgeCases = "None.",
            security = "Deliberately combines process input and a host path.",
            resultFormats = ToolResultFormat.PLAINTEXT,
            examples = {
                "{\"input\":\"yes\",\"path\":\"/tmp/one\"}",
                "{\"input\":\"no\",\"path\":\"/tmp/two\"}",
                "{\"input\":\"quit\",\"path\":\"/tmp/three\"}"
            },
            returnExamples = {"ok", "ok", "ok"})
    private static final class InvalidProcessInputTool
            implements NativeTool<InvalidProcessInputTool.Args> {
        private record Args(
                @NonNull @SecurityHint(ParamCategory.PROCESS_INPUT) @Doc("Process input.")
                        String input,
                @NonNull @SecurityHint(ParamCategory.FILESYSTEM_PATH) @Doc("Host path.")
                        String path) {}

        @Override
        public @NonNull String getName() {
            return "invalid_process_input";
        }

        @Override
        public @NonNull Class<Args> getArgsClass() {
            return ToolDocs.nonNullClass(Args.class);
        }

        @Override
        public @NonNull String execute(@NonNull Args args) {
            return "ok";
        }
    }
}
