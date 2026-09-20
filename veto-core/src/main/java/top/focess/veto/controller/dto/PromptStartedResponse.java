package top.focess.veto.controller.dto;

import org.jspecify.annotations.NonNull;

public record PromptStartedResponse(@NonNull String status, @NonNull String sessionId)
        implements RestResponse {}
