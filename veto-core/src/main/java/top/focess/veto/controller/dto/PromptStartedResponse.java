package top.focess.veto.controller.dto;

import org.jspecify.annotations.NonNull;

/** Payload confirming that a prompt was started for a session. */
public record PromptStartedResponse(@NonNull String status, @NonNull String sessionId)
        implements RestResponse {}
