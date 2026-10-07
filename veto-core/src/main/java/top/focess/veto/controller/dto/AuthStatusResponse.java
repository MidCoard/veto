package top.focess.veto.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;
import org.jspecify.annotations.*;

/** Payload reporting setup, vault-lock, and client login-session state for an auth status query. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthStatusResponse(
        boolean setupNeeded,
        boolean vaultLocked,
        int activeSessions, // Counts client logins, not agent conversations.
        UUID userId,
        @NonNull String timestamp,
        boolean authenticated,
        String username)
        implements RestResponse {}
