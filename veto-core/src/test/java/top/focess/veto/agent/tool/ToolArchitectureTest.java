package top.focess.veto.agent.tool;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Checks actual registered tools so a new tool cannot bypass the restricted execution boundary. */
@SpringBootTest
@SuppressWarnings("initialization.field.uninitialized")
class ToolArchitectureTest {
    @Autowired private @NonNull List<NativeTool<?>> nativeTools;
    @Autowired private @NonNull List<AgentTool<?>> agentTools;

    @Test
    void everyRegisteredToolUsesItsDeclaredBoundaryAndOnlyRestrictedDependencies() {
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
                                        agentTool.getArgsClass(),
                                        agentTool.getCapability());
                        default ->
                                throw new AssertionError(
                                        "Unexpected registered handler " + tool.getName());
                    };
            assertDoesNotThrow(
                    () -> ToolContractValidator.validateHandler(tool, definition), tool.getName());
        }
    }
}
