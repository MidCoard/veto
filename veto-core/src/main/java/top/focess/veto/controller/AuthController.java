package top.focess.veto.controller;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
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
@SuppressWarnings(
        "DuplicatedCode") // Setup and admin creation deliberately share validation responses.
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
    public @NonNull ResponseEntity<Map<String, @NonNull Object>> setup(
            @RequestBody @NonNull Map<String, String> request) {
        String username = request.get("username");
        String password = request.get("password");

        if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(
                            Map.of(
                                    "status",
                                    "error",
                                    "message",
                                    Msg.get("error.auth.credentialsRequired")));
        }
        if (!UserRegistry.isValidUsername(username)) {
            return ResponseEntity.badRequest()
                    .body(
                            Map.of(
                                    "status",
                                    "error",
                                    "message",
                                    Msg.get("error.auth.invalidUsername")));
        }
        if (password.length() < 8) {
            return ResponseEntity.badRequest()
                    .body(
                            Map.of(
                                    "status",
                                    "error",
                                    "message",
                                    Msg.get("error.auth.passwordTooShort")));
        }
        if (userRegistry.anyUserExists()) {
            return ResponseEntity.status(409)
                    .body(Map.of("status", "error", "message", Msg.get("error.auth.alreadySetup")));
        }

        try {
            userRegistry.create(username, password, UserRegistry.Role.ADMIN);
            authLifecycleManager.signup(username, password);
            String token = sessionManager.createSession(username);

            log.info("Vault setup complete - admin user '{}' created", username);
            return ResponseEntity.ok(
                    Map.of(
                            "status",
                            "ok",
                            "token",
                            token,
                            "username",
                            username,
                            "role",
                            "ADMIN",
                            "message",
                            "Vault initialized and unlocked"));
        } catch (Exception e) {
            log.error("Setup failed", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", Msg.get("error.auth.setupFailed")));
        }
    }

    // ── Login ───────────────────────────────────────────────────────────────

    /** POST /api/auth/login - Authenticate and unlock the user's credential vault. */
    @PostMapping(
            value = "/login",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public @NonNull ResponseEntity<Map<String, @NonNull Object>> login(
            @RequestBody @NonNull Map<String, String> request) {
        String username = request.get("username");
        String password = request.get("password");

        if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(
                            Map.of(
                                    "status",
                                    "error",
                                    "message",
                                    Msg.get("error.auth.credentialsRequired")));
        }

        var user = userRegistry.authenticate(username, password);
        if (user.isEmpty()) {
            return ResponseEntity.status(401)
                    .body(
                            Map.of(
                                    "status",
                                    "error",
                                    "message",
                                    Msg.get("error.auth.invalidCredentials")));
        }

        try {
            authLifecycleManager.login(username, password);
            String token = sessionManager.createSession(username);

            log.info("User '{}' logged in", username);
            return ResponseEntity.ok(
                    Map.of(
                            "status",
                            "ok",
                            "token",
                            token,
                            "username",
                            username,
                            "role",
                            user.get().getRole()));
        } catch (Exception e) {
            log.error("Login failed for user '{}'", username, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", Msg.get("error.auth.loginFailed")));
        }
    }

    // ── Logout ──────────────────────────────────────────────────────────────

    /** POST /api/auth/logout - Invalidate session and lock vault if no other sessions active. */
    @PostMapping(value = "/logout", produces = MediaType.APPLICATION_JSON_VALUE)
    public @NonNull ResponseEntity<Map<String, @NonNull Object>> logout(
            @RequestHeader(TOKEN_HEADER) @NonNull String token) {
        var session = sessionManager.validate(token);
        if (session.isEmpty()) {
            return ResponseEntity.status(401)
                    .body(
                            Map.of(
                                    "status",
                                    "error",
                                    "message",
                                    Msg.get("error.auth.invalidSession")));
        }

        sessionManager.invalidate(token);

        if (sessionManager.activeSessionCount() == 0) {
            authLifecycleManager.logout(session.get().username());
        }

        return ResponseEntity.ok(
                Map.of(
                        "status",
                        "ok",
                        "message",
                        "Logged out",
                        "username",
                        session.get().username()));
    }

    // ── Status ─────────────────────────────────────────────────────────────

    /** GET /api/auth/status - Returns vault and session state. */
    @GetMapping(value = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public @NonNull ResponseEntity<Map<String, Object>> status(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        boolean setupNeeded = !userRegistry.anyUserExists();
        boolean vaultLocked = !vault.isUnlocked();

        var result = new LinkedHashMap<String, Object>();
        result.put("setupNeeded", setupNeeded);
        result.put("vaultLocked", vaultLocked);
        result.put("activeSessions", sessionManager.activeSessionCount());
        String currentUser = vault.currentUser();
        if (currentUser != null) {
            result.put("currentUser", currentUser);
        }
        result.put("timestamp", Instant.now().toString());

        if (token != null) {
            var session = sessionManager.validate(token);
            result.put("authenticated", session.isPresent());
            session.ifPresent(s -> result.put("username", s.username()));
        } else {
            result.put("authenticated", false);
        }

        return ResponseEntity.ok(result);
    }

    // ── User management (admin only) ────────────────────────────────────────

    /** POST /api/auth/users - Add a new user with their own vault. Requires admin session. */
    @PostMapping(
            value = "/users",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    // User-controlled fields below are validated and serialized as application/json by Jackson.
    @SuppressWarnings("JvmTaintAnalysis")
    public @NonNull ResponseEntity<Map<String, Object>> addUser(
            @RequestHeader(TOKEN_HEADER) @NonNull String token,
            @RequestBody @NonNull Map<String, String> request) {

        var session = sessionManager.validate(token);
        if (session.isEmpty()) {
            return ResponseEntity.status(401)
                    .body(
                            Map.of(
                                    "status",
                                    "error",
                                    "message",
                                    Msg.get("error.auth.invalidSession")));
        }

        // Verify admin role
        var adminEntry = userRegistry.findByUsername(session.get().username());
        if (adminEntry.isEmpty() || !"ADMIN".equals(adminEntry.get().getRole())) {
            return ResponseEntity.status(403)
                    .body(
                            Map.of(
                                    "status",
                                    "error",
                                    "message",
                                    Msg.get("error.auth.adminRequired")));
        }

        String username = request.get("username");
        String password = request.get("password");
        String requestedRole = request.get("role");
        String role =
                requestedRole == null
                        ? UserRegistry.Role.USER
                        : requestedRole.trim().toUpperCase(Locale.ROOT);

        if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(
                            Map.of(
                                    "status",
                                    "error",
                                    "message",
                                    Msg.get("error.auth.credentialsRequired")));
        }
        if (!UserRegistry.isValidUsername(username)) {
            return ResponseEntity.badRequest()
                    .body(
                            Map.of(
                                    "status",
                                    "error",
                                    "message",
                                    Msg.get("error.auth.invalidUsername")));
        }
        if (password.length() < 8) {
            return ResponseEntity.badRequest()
                    .body(
                            Map.of(
                                    "status",
                                    "error",
                                    "message",
                                    Msg.get("error.auth.passwordTooShort")));
        }
        if (!UserRegistry.isValidRole(role)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", Msg.get("error.auth.invalidRole")));
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
            return ResponseEntity.ok(
                    Map.of(
                            "status",
                            "ok",
                            "username",
                            username,
                            "role",
                            role,
                            "message",
                            "User created"));
        } catch (IllegalArgumentException e) {
            // Duplicate username (UserRegistry.create rejects an existing id).
            return ResponseEntity.status(409)
                    .body(
                            Map.of(
                                    "status",
                                    "error",
                                    "message",
                                    Msg.get("error.auth.userExists", username)));
        } catch (Exception e) {
            log.error("Failed to create user '{}'", username, e);
            return ResponseEntity.internalServerError()
                    .body(
                            Map.of(
                                    "status",
                                    "error",
                                    "message",
                                    Msg.get("error.auth.createUserFailed")));
        }
    }
}
