package top.focess.veto.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.*;

/** Payload reporting setup, vault-lock, and session state for an auth status query. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthStatusResponse(
        boolean setupNeeded,
        boolean vaultLocked,
        int activeSessions,
        String currentUser,
        @NonNull String timestamp,
        boolean authenticated,
        String username)
        implements RestResponse {}
