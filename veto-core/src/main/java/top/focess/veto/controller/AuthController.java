package top.focess.veto.controller;

import java.time.Instant;
import java.util.Locale;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.focess.veto.controller.dto.*;
import top.focess.veto.controller.dto.AuthCredentials;
import top.focess.veto.controller.dto.CreateUserRequest;
import top.focess.veto.i18n.Msg;
import top.focess.veto.vault.*;

/**
 * REST controller for authentication and vault lifecycle. Provides endpoints for first-run setup,
 * login, logout, status, and admin user management.
 *
 * <p>The vault is keystead-backed: each user has their own vault, created and opened with their
 * login password. No user can read another user's secrets.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.controller.AuthController");

    private static final String TOKEN_HEADER = "X-Veto-Session-Token";

    private final @NonNull UserRegistry userRegistry;
    private final @NonNull SessionManager sessionManager;
    private final @NonNull KeysteadVault vault;
    private final @NonNull AuthLifecycleManager authLifecycleManager;

    public AuthController(
            @NonNull UserRegistry userRegistry,
            @NonNull SessionManager sessionManager,
            @NonNull KeysteadVault vault,
            @NonNull AuthLifecycleManager authLifecycleManager) {
        this.userRegistry = userRegistry;
        this.sessionManager = sessionManager;
        this.vault = vault;
        this.authLifecycleManager = authLifecycleManager;
    }

    // ── Setup (first-run) ───────────────────────────────────────────────────

    /** POST /api/auth/setup - First-run admin creation. Only works when no users exist. */
    @PostMapping(
            value = "/setup",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public @NonNull ResponseEntity<RestResponse> setup(
            @RequestBody @NonNull AuthCredentials request) {
        String username = request.username();
        String password = request.password();

        if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(error(Msg.get("error.auth.credentialsRequired")));
        }
        String registrationError = registrationError(username, password);
        if (registrationError != null) {
            return ResponseEntity.badRequest().body(error(registrationError));
        }
        if (userRegistry.anyUserExists()) {
            return ResponseEntity.status(409).body(error(Msg.get("error.auth.alreadySetup")));
        }

        try {
            userRegistry.create(username, password, UserRegistry.Role.ADMIN);
            authLifecycleManager.signup(username, password);
            String token = sessionManager.createSession(username);

            log.info("Vault setup complete - admin user '{}' created", username);
            return ResponseEntity.ok(
                    new AuthSetupResponse(
                            "ok", token, username, "ADMIN", "Vault initialized and unlocked"));
        } catch (Exception e) {
            log.error("Setup failed", e);
            return ResponseEntity.internalServerError()
                    .body(error(Msg.get("error.auth.setupFailed")));
        }
    }

    // ── Login ───────────────────────────────────────────────────────────────

    /** POST /api/auth/login - Authenticate and unlock the user's credential vault. */
    @PostMapping(
            value = "/login",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public @NonNull ResponseEntity<RestResponse> login(
            @RequestBody @NonNull AuthCredentials request) {
        String username = request.username();
        String password = request.password();

        if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(error(Msg.get("error.auth.credentialsRequired")));
        }

        var user = userRegistry.authenticate(username, password);
        if (user.isEmpty()) {
            return ResponseEntity.status(401).body(error(Msg.get("error.auth.invalidCredentials")));
        }

        try {
            authLifecycleManager.login(username, password);
            String token = sessionManager.createSession(username);

            log.info("User '{}' logged in", username);
            return ResponseEntity.ok(
                    new AuthSessionResponse("ok", token, username, user.get().getRole()));
        } catch (Exception e) {
            log.error("Login failed for user '{}'", username, e);
            return ResponseEntity.internalServerError()
                    .body(error(Msg.get("error.auth.loginFailed")));
        }
    }

    // ── Logout ──────────────────────────────────────────────────────────────

    /** POST /api/auth/logout - Invalidate session and lock vault if no other sessions active. */
    @PostMapping(value = "/logout", produces = MediaType.APPLICATION_JSON_VALUE)
    public @NonNull ResponseEntity<RestResponse> logout(
            @RequestHeader(TOKEN_HEADER) @NonNull String token) {
        var session = sessionManager.validate(token);
        if (session.isEmpty()) {
            return ResponseEntity.status(401).body(error(Msg.get("error.auth.invalidSession")));
        }

        sessionManager.invalidate(token);

        if (sessionManager.activeSessionCount() == 0) {
            authLifecycleManager.logout(session.get().username());
        }

        return ResponseEntity.ok(
                new AuthLogoutResponse("ok", "Logged out", session.get().username()));
    }

    // ── Status ─────────────────────────────────────────────────────────────

    /** GET /api/auth/status - Returns vault and session state. */
    @GetMapping(value = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public @NonNull ResponseEntity<RestResponse> status(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        boolean setupNeeded = !userRegistry.anyUserExists();
        boolean vaultLocked = !vault.isUnlocked();

        var session = sessionManager.validate(token == null ? "" : token);
        return ResponseEntity.ok(
                new AuthStatusResponse(
                        setupNeeded,
                        vaultLocked,
                        sessionManager.activeSessionCount(),
                        vault.currentUser(),
                        Instant.now().toString(),
                        session.isPresent(),
                        session.map(value -> value.username()).orElse(null)));
    }

    // ── User management (admin only) ────────────────────────────────────────

    /** POST /api/auth/users - Add a new user with their own vault. Requires admin session. */
    @PostMapping(
            value = "/users",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    // User-controlled fields below are validated and serialized as application/json by Jackson.
    @SuppressWarnings("JvmTaintAnalysis")
    public @NonNull ResponseEntity<RestResponse> addUser(
            @RequestHeader(TOKEN_HEADER) @NonNull String token,
            @RequestBody @NonNull CreateUserRequest request) {

        var session = sessionManager.validate(token);
        if (session.isEmpty()) {
            return ResponseEntity.status(401).body(error(Msg.get("error.auth.invalidSession")));
        }

        // Verify admin role
        var adminEntry = userRegistry.findByUsername(session.get().username());
        if (adminEntry.isEmpty() || !"ADMIN".equals(adminEntry.get().getRole())) {
            return ResponseEntity.status(403).body(error(Msg.get("error.auth.adminRequired")));
        }

        String username = request.username();
        String password = request.password();
        String requestedRole = request.role();
        String role =
                requestedRole == null
                        ? UserRegistry.Role.USER
                        : requestedRole.trim().toUpperCase(Locale.ROOT);

        if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(error(Msg.get("error.auth.credentialsRequired")));
        }
        String registrationError = registrationError(username, password);
        if (registrationError != null) {
            return ResponseEntity.badRequest().body(error(registrationError));
        }
        if (!UserRegistry.isValidRole(role)) {
            return ResponseEntity.badRequest().body(error(Msg.get("error.auth.invalidRole")));
        }

        try {
            userRegistry.create(username, password, role);
            // Provision the new user's vault (created closed; opened when they log in).
            vault.createVault(username, password);

            log.info(
                    "Admin '{}' created user '{}' with role '{}'",
                    session.get().username(),
                    username,
                    role);
            return ResponseEntity.ok(new UserCreatedResponse("ok", username, role, "User created"));
        } catch (IllegalArgumentException e) {
            // Duplicate username (UserRegistry.create rejects an existing id).
            return ResponseEntity.status(409) // TODO check the security problem
                    .body(error(Msg.get("error.auth.userExists", username)));
        } catch (Exception e) {
            log.error("Failed to create user '{}'", username, e);
            return ResponseEntity.internalServerError()
                    .body(error(Msg.get("error.auth.createUserFailed")));
        }
    }

    private static String registrationError(@NonNull String username, @NonNull String password) {
        if (!UserRegistry.isValidUsername(username)) return Msg.get("error.auth.invalidUsername");
        if (password.length() < 8) return Msg.get("error.auth.passwordTooShort");
        return null;
    }

    private static @NonNull StatusMessageResponse error(@NonNull String message) {
        return new StatusMessageResponse("error", message);
    }
}
