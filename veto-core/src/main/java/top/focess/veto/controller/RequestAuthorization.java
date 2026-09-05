package top.focess.veto.controller;

import java.util.function.Predicate;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
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

    private final @NonNull Predicate<@NonNull String> administrator;

    @Autowired
    public RequestAuthorization(@NonNull UserRegistry users) {
        this(users::isAdmin);
    }

    public RequestAuthorization(@NonNull Predicate<@NonNull String> administrator) {
        this.administrator = administrator;
    }

    public @NonNull String requireUser() {
        String username = UserContext.get();
        if (username == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return username;
    }

    public void requireAdmin() {
        String username = requireUser();
        if (!administrator.test(username)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrator role required");
        }
    }

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
