package top.focess.veto.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.*;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskSummaryResponse(
        @NonNull String id,
        @NonNull String taskType,
        @NonNull String status,
        @NonNull String createdAt)
        implements RestResponse {}
