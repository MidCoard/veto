package top.focess.veto.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;
import org.jspecify.annotations.*;

/** Payload confirming that the caller's session was invalidated on logout. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthLogoutResponse(
        @NonNull String status,
        @NonNull String message,
        @NonNull UUID userId,
        @NonNull String username)
        implements RestResponse {}
