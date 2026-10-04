package top.focess.veto.controller;

import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import top.focess.veto.i18n.Msg;
import top.focess.veto.session.SessionService;
import top.focess.veto.vault.KeysteadVault;
import top.focess.veto.vault.UserContext;
import top.focess.veto.vault.UserRegistry;

/** Enforces request-scoped authentication and administrator authorization. */
@Component
public class RequestAuthorization {

    private final @NonNull UserRegistry users;

    /** Creates the authorizer using the user registry's admin check. */
    public RequestAuthorization(@NonNull UserRegistry users) {
        this.users = users;
    }

    /** Returns the request-scoped username, or throws 401 if the request is unauthenticated. */
    public @NonNull String requireUser() {
        String username = UserContext.get();
        if (username == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return username;
    }

    /** Requires an authenticated administrator; throws 401 or 403 otherwise. */
    public void requireAdmin() {
        String username = requireUser();
        if (!users.isAdmin(username)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrator role required");
        }
    }

    /**
     * Resolves the primary agent id of a session owned by the current vault user; throws 401 when
     * unauthenticated or 404 when the session is unknown.
     */
    public static @NonNull String requireAgentId(
            @NonNull String name, @NonNull SessionService sessions, @NonNull KeysteadVault vault) {
        String user = vault.currentUser();
        if (user == null) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, Msg.get("error.auth.notAuthenticated"));
        }
        return sessions.primaryAgentIdFor(name, user)
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND,
                                        Msg.get("error.session.notFound", name)));
    }
}
