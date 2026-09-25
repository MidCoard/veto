package top.focess.veto.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.*;

/** Payload confirming task creation and reporting its id and DAG status. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskCreatedResponse(
        @NonNull String status,
        @NonNull String id,
        @NonNull String taskType,
        @NonNull String dagStatus,
        @NonNull String timestamp)
        implements RestResponse {}
