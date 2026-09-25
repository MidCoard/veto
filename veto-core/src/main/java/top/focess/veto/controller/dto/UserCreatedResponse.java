package top.focess.veto.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.*;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserCreatedResponse(
        @NonNull String status,
        @NonNull String username,
        @NonNull String role,
        @NonNull String message)
        implements RestResponse {}
