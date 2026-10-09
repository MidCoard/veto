package top.focess.veto.controller;

import java.time.Instant;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.focess.veto.controller.dto.*;
import top.focess.veto.i18n.Msg;
import top.focess.veto.vault.AuthException;
import top.focess.veto.vault.AuthService;
import top.focess.veto.vault.LoginSessionManager.LoginSession;

/** HTTP input/output adapter for the shared authentication workflows. */
@RestController
@RequestMapping(value = "/api/auth", produces = MediaType.APPLICATION_JSON_VALUE)
public class AuthController {
    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.controller.AuthController");
    private static final String TOKEN_HEADER = "X-Veto-Session-Token";
    private final @NonNull AuthService auth;

    public AuthController(@NonNull AuthService auth) {
        this.auth = auth;
    }

    @PostMapping(value = "/setup", consumes = MediaType.APPLICATION_JSON_VALUE)
    public @NonNull ResponseEntity<RestResponse> setup(
            @RequestBody @NonNull AuthCredentials request) {
        try {
            var login = auth.setup(request.username(), request.password());
            var user = login.user();
            return ResponseEntity.ok(
                    new AuthSetupResponse(
                            "ok",
                            login.token(),
                            user.getUserId(),
                            user.getUsername(),
                            user.getRole(),
                            "Vault initialized and unlocked"));
        } catch (RuntimeException failure) {
            return failure(failure, "error.auth.setupFailed");
        }
    }

    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public @NonNull ResponseEntity<RestResponse> login(
            @RequestBody @NonNull AuthCredentials request) {
        try {
            var login = auth.login(request.username(), request.password());
            var user = login.user();
            return ResponseEntity.ok(
                    new AuthLoginResponse(
                            "ok",
                            login.token(),
                            user.getUserId(),
                            user.getUsername(),
                            user.getRole()));
        } catch (RuntimeException failure) {
            return failure(failure, "error.auth.loginFailed");
        }
    }

    @PostMapping("/logout")
    public @NonNull ResponseEntity<RestResponse> logout(
            @RequestHeader(TOKEN_HEADER) @NonNull String token) {
        var login = auth.logout(token);
        return ResponseEntity.ok(
                new AuthLogoutResponse("ok", "Logged out", login.userId(), login.username()));
    }

    @GetMapping("/status")
    public @NonNull ResponseEntity<RestResponse> status(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        var status = auth.status(token);
        return ResponseEntity.ok(
                new AuthStatusResponse(
                        status.setupNeeded(),
                        status.vaultLocked(),
                        status.activeSessions(),
                        status.login().map(LoginSession::userId).orElse(null),
                        Instant.now().toString(),
                        status.login().isPresent(),
                        status.login().map(LoginSession::username).orElse(null)));
    }

    @PostMapping(value = "/users", consumes = MediaType.APPLICATION_JSON_VALUE)
    public @NonNull ResponseEntity<RestResponse> addUser(
            @RequestHeader(TOKEN_HEADER) @NonNull String token,
            @RequestBody @NonNull CreateUserRequest request) {
        try {
            var user =
                    auth.createUser(token, request.username(), request.password(), request.role());
            return ResponseEntity.ok(
                    new UserCreatedResponse(
                            "ok",
                            user.getUserId(),
                            user.getUsername(),
                            user.getRole(),
                            "User created"));
        } catch (RuntimeException failure) {
            return failure(failure, "error.auth.createUserFailed");
        }
    }

    @ExceptionHandler(AuthException.class)
    public @NonNull ResponseEntity<RestResponse> rejected(@NonNull AuthException rejection) {
        int status =
                switch (rejection.kind()) {
                    case INVALID_INPUT -> 400;
                    case INVALID_CREDENTIALS, INVALID_SESSION -> 401;
                    case FORBIDDEN -> 403;
                    case CONFLICT -> 409;
                    case INTERNAL -> 500;
                };
        return ResponseEntity.status(status)
                .body(new StatusMessageResponse("error", rejection.getMessage()));
    }

    private static @NonNull ResponseEntity<RestResponse> failure(
            @NonNull RuntimeException failure, @NonNull String messageKey) {
        if (failure instanceof AuthException rejection) throw rejection;
        log.error("Authentication operation failed", failure);
        return ResponseEntity.internalServerError()
                .body(new StatusMessageResponse("error", Msg.get(messageKey)));
    }
}
