package top.focess.veto.agent.tool;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import top.focess.veto.api.agent.tool.AgentTool;
import top.focess.veto.api.agent.tool.CapabilityTool;
import top.focess.veto.api.agent.tool.NativeTool;
import top.focess.veto.api.agent.tool.ToolDoc;
import top.focess.veto.api.agent.tool.ToolDocs;

/** Checks that registered tools expose coherent authoring contracts. */
@SpringBootTest
@SuppressWarnings("initialization.field.uninitialized")
class ToolArchitectureTest {
    @Autowired private @NonNull List<NativeTool<?>> nativeTools;
    @Autowired private @NonNull List<AgentTool<?>> agentTools;

    @Test
    void everyRegisteredToolHasACoherentContract() {
        List<CapabilityTool<?>> tools = new ArrayList<>();
        tools.addAll(nativeTools);
        tools.addAll(agentTools);
        assertFalse(tools.isEmpty());
        for (CapabilityTool<?> tool : tools) {
            ToolDefinition definition =
                    switch (tool) {
                        case NativeTool<?> nativeTool ->
                                ToolSchemaCompiler.compileNative(nativeTool);
                        case AgentTool<?> agentTool ->
                                AgentToolDefinition.from(
                                        agentTool.getName(),
                                        agentTool.getClass(),
                                        agentTool.getArgsClass(),
                                        agentTool.getCapability());
                        default ->
                                throw new AssertionError(
                                        "Unexpected registered handler " + tool.getName());
                    };
            assertDoesNotThrow(
                    () -> ToolContractValidator.validateHandler(tool, definition), tool.getName());
            ToolDoc documentation = ToolDocs.toolDocOf(tool.getClass());
            if (documentation == null) {
                throw new AssertionError("Missing ToolDoc on " + tool.getName());
            }
            assertNull(ToolDocs.toolDocOf(tool.getArgsClass()), tool.getName());
            assertEquals(List.of(documentation.examples()), definition.examples(), tool.getName());
            assertEquals(
                    List.of(documentation.returnExamples()),
                    definition.returnExamples(),
                    tool.getName());
            assertEquals(
                    ToolDocs.documentationOf(tool.getClass()),
                    definition.documentation(),
                    tool.getName());
        }
    }
}
