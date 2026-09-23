package top.focess.veto.agent.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.capability.CapabilityResolver;
import top.focess.veto.agent.capability.ProtectedWorkspaceReadCapabilityImpl;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.api.agent.capability.WorkspaceWriteCapability;
import top.focess.veto.api.agent.tool.CapabilityTool;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.agent.tool.WorkspaceReadTool;
import top.focess.veto.api.agent.tool.WorkspaceWriteTool;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.ToolResultPresentationMode;

/** Creates an exact approved-call scope for direct capability integration tests. */
public final class CapabilityTestCalls {
    private CapabilityTestCalls() {}

    @SuppressWarnings(
            "unchecked") // The tool's typed workspace interface shares its argument type T.
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
                        permit,
                        previous.requestId()));
        ToolCallContextHolder.setCurrentCallId(callId);
        try {
            if (tool instanceof WorkspaceReadTool<?> read)
                return ((WorkspaceReadTool<T>) read)
                        .execute(args, new ProtectedWorkspaceReadCapabilityImpl());
            if (tool instanceof WorkspaceWriteTool<?> write)
                return ((WorkspaceWriteTool<T>) write)
                        .execute(
                                args,
                                CapabilityResolver.require(
                                        ToolDocs.nonNullClass(WorkspaceWriteCapability.class)));
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
