package top.focess.veto.agent.tool;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.api.llm.ToolResultPresentationMode;

/** Trusted host caller identity, authorization and request correlation. */
public record ToolCallContext(
        @NonNull String agentId,
        @NonNull UUID userId,
        String owner,
        UUID sessionId,
        @NonNull ToolResultPresentationMode toolResultPresentation,
        @NonNull ToolExecutionPermit executionPermit,
        String requestId) {
    public ToolCallContext(
            @NonNull String agentId,
            @NonNull UUID userId,
            String owner,
            UUID sessionId,
            @NonNull ToolResultPresentationMode toolResultPresentation,
            @NonNull ToolExecutionPermit executionPermit) {
        this(agentId, userId, owner, sessionId, toolResultPresentation, executionPermit, null);
    }
}
