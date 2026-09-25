package top.focess.veto.controller.dto;

import org.jspecify.annotations.NonNull;

/** Payload confirming that a prompt was queued for an agent session. */
public record AgentPromptQueuedResponse(
        @NonNull String status, @NonNull String sessionId, @NonNull String agentId)
        implements RestResponse {}
