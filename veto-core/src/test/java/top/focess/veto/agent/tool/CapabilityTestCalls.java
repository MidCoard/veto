package top.focess.veto.agent.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.llm.core.ToolCall;
import top.focess.veto.llm.core.ToolResultPresentationMode;

/** Creates an exact approved-call scope for direct capability integration tests. */
public final class CapabilityTestCalls {
    private CapabilityTestCalls() {}

    public static <T> @NonNull String execute(@NonNull CapabilityTool<T> tool, @NonNull T args)
            throws Exception {
        ToolCallContext previous = ToolCallContextHolder.get();
        boolean hadContext = previous != null;
        if (previous == null)
            previous =
                    new ToolCallContext(
                            "test-agent",
                            UUID.randomUUID(),
                            null,
                            "test-owner",
                            UUID.randomUUID(),
                            ToolResultPresentationMode.BASIC,
                            false,
                            ToolExecutionPermit.empty());
        String priorCall = ToolCallContextHolder.currentCallId();
        String callId = UUID.randomUUID().toString();
        Map<String, Object> values =
                new ObjectMapper().convertValue(args, new TypeReference<Map<String, Object>>() {});
        values.values().removeIf(value -> value == null);
        ToolExecutionPermit old = previous.executionPermit();
        ToolExecutionPermit permit =
                new ToolExecutionPermit(
                                new ToolCall(tool.getName(), values, callId),
                                tool.getCapability(),
                                null,
                                null,
                                old.filesystemPaths(),
                                old.workspaceRoots(),
                                old.executionRoot(),
                                old.deployerPolicy(),
                                old.protectedPaths(),
                                old.taskBinding())
                        .withCaller(
                                previous.agentId(),
                                previous.userId(),
                                previous.groupId(),
                                previous.owner(),
                                previous.sessionId());
        ToolCallContextHolder.set(
                new ToolCallContext(
                        previous.agentId(),
                        previous.userId(),
                        previous.groupId(),
                        previous.owner(),
                        previous.sessionId(),
                        previous.toolResultPresentation(),
                        previous.guidedEnabled(),
                        permit));
        ToolCallContextHolder.setCurrentCallId(callId);
        try {
            return tool.execute(args);
        } finally {
            if (!hadContext) {
                ToolCallContextHolder.clear();
            } else {
                ToolCallContextHolder.set(previous);
                if (priorCall != null) ToolCallContextHolder.setCurrentCallId(priorCall);
                else ToolCallContextHolder.setCurrentCallId("");
            }
        }
    }
}
