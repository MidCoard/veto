package top.focess.veto.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.*;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record VetoProcessResponse(
        @NonNull String status,
        @NonNull String decision,
        @NonNull String processedPayload,
        @NonNull String reason,
        int redactionCount,
        boolean allowed,
        @NonNull String timestamp)
        implements RestResponse {}
