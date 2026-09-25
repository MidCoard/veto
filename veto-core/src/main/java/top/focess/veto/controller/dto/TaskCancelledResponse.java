package top.focess.veto.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.*;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskCancelledResponse(
        @NonNull String status,
        @NonNull String id,
        @NonNull String newStatus,
        @NonNull String timestamp)
        implements RestResponse {}
