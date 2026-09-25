package top.focess.veto.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.*;

/** Payload confirming that the caller's session was invalidated on logout. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthLogoutResponse(
        @NonNull String status, @NonNull String message, @NonNull String username)
        implements RestResponse {}
