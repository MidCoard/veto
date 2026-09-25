package top.focess.veto.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.*;

/** Payload carrying a status and a human-readable message. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StatusMessageResponse(@NonNull String status, @NonNull String message)
        implements RestResponse {}
