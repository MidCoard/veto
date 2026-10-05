package top.focess.veto.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;
import org.jspecify.annotations.*;

/** Payload confirming that a new user was created with the given role. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserCreatedResponse(
        @NonNull String status,
        @NonNull UUID userId,
        @NonNull String username,
        @NonNull String role,
        @NonNull String message)
        implements RestResponse {}
