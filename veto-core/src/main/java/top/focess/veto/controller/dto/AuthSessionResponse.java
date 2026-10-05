package top.focess.veto.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;
import org.jspecify.annotations.*;

/** Payload returned on successful authentication, carrying the new session token and user role. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthSessionResponse(
        @NonNull String status,
        @NonNull String token,
        @NonNull UUID userId,
        @NonNull String username,
        @NonNull String role)
        implements RestResponse {}
