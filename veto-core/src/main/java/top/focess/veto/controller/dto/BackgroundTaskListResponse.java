package top.focess.veto.controller.dto;

import org.jspecify.annotations.*;

@com.fasterxml.jackson.annotation.JsonInclude(
        com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record BackgroundTaskListResponse(
        @NonNull String status, java.util.@NonNull List<BackgroundTaskResponse> tasks)
        implements RestResponse {}
