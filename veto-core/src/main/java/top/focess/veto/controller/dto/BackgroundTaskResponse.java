package top.focess.veto.controller.dto;

import org.jspecify.annotations.*;

@com.fasterxml.jackson.annotation.JsonInclude(
        com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record BackgroundTaskResponse(
        @NonNull String taskId,
        @NonNull String command,
        @NonNull String cwd,
        long pid,
        boolean alive,
        @Nullable Integer exitCode,
        @NonNull String startedAt,
        @Nullable String finishedAt,
        long uptimeSeconds,
        @Nullable String recentOutput)
        implements RestResponse {}
