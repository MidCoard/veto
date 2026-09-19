package top.focess.veto.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.*;
import org.jspecify.annotations.*;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthSetupResponse(
        @NonNull String status,
        @NonNull String token,
        @NonNull String username,
        @NonNull String role,
        @NonNull String message)
        implements RestResponse {}
