package top.focess.veto.controller;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
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

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

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
            @NonNull @RequestBody Map<String, String> request) {
        String username = request.get("username");
        String password = request.get("password");

        if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(
                            Map.of(
                                    "status",
                                    "error",
                                    "message",
                                    "username and password are required"));
        }
        if (password.length() < 8) {
            return ResponseEntity.badRequest()
                    .body(
                            Map.of(
                                    "status",
                                    "error",
                                    "message",
                                    "password must be at least 8 characters"));
        }
        if (userRegistry.anyUserExists()) {
            return ResponseEntity.status(409)
                    .body(
                            Map.of(
                                    "status",
                                    "error",
                                    "message",
                                    "Vault is already set up - use /login"));
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
                    .body(Map.of("status", "error", "message", "Setup failed: " + e.getMessage()));
        }
    }

    // ── Login ───────────────────────────────────────────────────────────────

    /** POST /api/auth/login - Authenticate and unlock the user's credential vault. */
    @PostMapping(
            value = "/login",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public @NonNull ResponseEntity<Map<String, @NonNull Object>> login(
            @NonNull @RequestBody Map<String, String> request) {
        String username = request.get("username");
        String password = request.get("password");

        if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(
                            Map.of(
                                    "status",
                                    "error",
                                    "message",
                                    "username and password are required"));
        }

        var user = userRegistry.authenticate(username, password);
        if (user.isEmpty()) {
            return ResponseEntity.status(401)
                    .body(Map.of("status", "error", "message", "Invalid username or password"));
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
                    .body(Map.of("status", "error", "message", "Login failed: " + e.getMessage()));
        }
    }

    // ── Logout ──────────────────────────────────────────────────────────────

    /** POST /api/auth/logout - Invalidate session and lock vault if no other sessions active. */
    @PostMapping(value = "/logout", produces = MediaType.APPLICATION_JSON_VALUE)
    public @NonNull ResponseEntity<Map<String, @NonNull Object>> logout(
            @NonNull @RequestHeader(TOKEN_HEADER) String token) {
        var session = sessionManager.validate(token);
        if (session.isEmpty()) {
            return ResponseEntity.status(401)
                    .body(Map.of("status", "error", "message", "Invalid or expired session"));
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
            @Nullable @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        boolean setupNeeded = !userRegistry.anyUserExists();
        boolean vaultLocked = !vault.isUnlocked();

        var result = new LinkedHashMap<String, Object>();
        result.put("setupNeeded", setupNeeded);
        result.put("vaultLocked", vaultLocked);
        result.put("activeSessions", sessionManager.activeSessionCount());
        result.put("currentUser", vault.currentUser());
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
    public @NonNull ResponseEntity<Map<String, Object>> addUser(
            @NonNull @RequestHeader(TOKEN_HEADER) String token,
            @NonNull @RequestBody Map<String, String> request) {

        var session = sessionManager.validate(token);
        if (session.isEmpty()) {
            return ResponseEntity.status(401)
                    .body(Map.of("status", "error", "message", "Invalid or expired session"));
        }

        // Verify admin role
        var adminEntry = userRegistry.findByUsername(session.get().username());
        if (adminEntry.isEmpty() || !"ADMIN".equals(adminEntry.get().getRole())) {
            return ResponseEntity.status(403)
                    .body(Map.of("status", "error", "message", "Admin privileges required"));
        }

        String username = request.get("username");
        String password = request.get("password");
        String role = request.getOrDefault("role", "USER");

        if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(
                            Map.of(
                                    "status",
                                    "error",
                                    "message",
                                    "username and password are required"));
        }
        if (password.length() < 8) {
            return ResponseEntity.badRequest()
                    .body(
                            Map.of(
                                    "status",
                                    "error",
                                    "message",
                                    "password must be at least 8 characters"));
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
            return ResponseEntity.status(409)
                    .body(Map.of("status", "error", "message", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to create user '{}'", username, e);
            return ResponseEntity.internalServerError()
                    .body(
                            Map.of(
                                    "status",
                                    "error",
                                    "message",
                                    "Failed to create user: " + e.getMessage()));
        }
    }
}
