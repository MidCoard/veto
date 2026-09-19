package top.focess.veto.controller.dto;

import org.jspecify.annotations.NonNull;

public record AgentPromptQueuedResponse(
        @NonNull String status, @NonNull String sessionId, @NonNull String agentId)
        implements RestResponse {}
