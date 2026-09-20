package top.focess.veto.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.*;
import org.jspecify.annotations.*;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthStatusResponse(
        boolean setupNeeded,
        boolean vaultLocked,
        int activeSessions,
        @Nullable String currentUser,
        @NonNull String timestamp,
        boolean authenticated,
        @Nullable String username)
        implements RestResponse {}
