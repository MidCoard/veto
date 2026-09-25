package top.focess.veto.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.*;

/** Payload returned after first-time vault setup creates the admin user and initial session. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthSetupResponse(
        @NonNull String status,
        @NonNull String token,
        @NonNull String username,
        @NonNull String role,
        @NonNull String message)
        implements RestResponse {}
