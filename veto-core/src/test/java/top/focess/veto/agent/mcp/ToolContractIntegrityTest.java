package top.focess.veto.agent.mcp;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Cross-checks every documented call example against the tool's real runtime argument validator.
 */
@SpringBootTest
@SuppressWarnings("initialization.field.uninitialized")
class ToolContractIntegrityTest {

    @Autowired private @NonNull List<NativeTool<?>> nativeTools;
    @Autowired private @NonNull List<AgentTool<?>> agentTools;

    private final @NonNull ObjectMapper mapper = new ObjectMapper();

    @Test
    void everyCallExamplePassesItsRuntimeArgumentValidator() {
        for (NativeTool<?> tool : nativeTools) {
            validateExamples(tool.getName(), tool.getArgsClass());
        }
        for (AgentTool<?> tool : agentTools) {
            validateExamples(tool.getName(), tool.getArgsClass());
        }
    }

    private void validateExamples(@NonNull String toolName, @NonNull Class<?> argsClass) {
        for (String example : ToolDocs.examplesOf(argsClass)) {
            assertDoesNotThrow(
                    () -> {
                        JsonNode args = mapper.readTree(example);
                        NativeToolArgumentValidator.validate(toolName, args, argsClass);
                        mapper.treeToValue(args, argsClass);
                    },
                    () -> toolName + " has an invalid call example: " + example);
        }
    }
}
