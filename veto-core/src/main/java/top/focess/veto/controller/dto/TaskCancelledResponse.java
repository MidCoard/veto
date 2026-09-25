package top.focess.veto.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.*;

/** Payload confirming that a task was cancelled and reporting its new status. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskCancelledResponse(
        @NonNull String status,
        @NonNull String id,
        @NonNull String newStatus,
        @NonNull String timestamp)
        implements RestResponse {}
