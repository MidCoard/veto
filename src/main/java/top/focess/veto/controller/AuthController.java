package top.focess.veto.controller;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.SecretKey;

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
 * <p>Each user gets their own Vault Key and credential file — no user can read another's secrets.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private static final String TOKEN_HEADER = "X-Veto-Session-Token";

    private final VaultKeyManager vaultKeyManager;
    private final UserRegistry userRegistry;
    private final SessionManager sessionManager;
    private final CredentialVault credentialVault;

    public AuthController(
            VaultKeyManager vaultKeyManager,
            UserRegistry userRegistry,
            SessionManager sessionManager,
            CredentialVault credentialVault) {
        this.vaultKeyManager = vaultKeyManager;
        this.userRegistry = userRegistry;
        this.sessionManager = sessionManager;
        this.credentialVault = credentialVault;
    }

    // ── Setup (first-run) ───────────────────────────────────────────────────

    /**
     * POST /api/auth/setup — First-run admin creation. Only works when no users exist.
     */
    @PostMapping(
            value = "/setup",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> setup(@RequestBody Map<String, String> request) {
        String username = request.get("username");
        String password = request.get("password");

        if (username == null || username.isBlank() || password == null || password.isBlank()) {
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
                                    "Vault is already set up — use /login"));
        }

        try {
            // Create admin user
            var userEntity = userRegistry.create(username, password, UserRegistry.Role.ADMIN);

            // Generate admin's own Vault Key
            SecretKey masterKey =
                    vaultKeyManager.deriveMasterKey(
                            username, password, userEntity.getPasswordSalt());
            SecretKey vaultKey = vaultKeyManager.generateVaultKey();
            vaultKeyManager.wrapVaultKey(vaultKey, masterKey, username);

            // Unlock the vault for admin
            credentialVault.unlock(vaultKey, username);
            String token = sessionManager.createSession(username);

            log.info("Vault setup complete — admin user '{}' created", username);
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

    /**
     * POST /api/auth/login — Authenticate and unlock the user's credential vault.
     */
    @PostMapping(
            value = "/login",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> request) {
        String username = request.get("username");
        String password = request.get("password");

        if (username == null || username.isBlank() || password == null || password.isBlank()) {
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
            SecretKey masterKey =
                    vaultKeyManager.deriveMasterKey(
                            username, password, user.get().getPasswordSalt());
            SecretKey vaultKey = vaultKeyManager.unwrapVaultKey(masterKey, username);
            if (vaultKey == null) {
                return ResponseEntity.status(500)
                        .body(
                                Map.of(
                                        "status",
                                        "error",
                                        "message",
                                        "Failed to unwrap vault key — vault may be corrupted"));
            }

            credentialVault.unlock(vaultKey, username);
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

    /**
     * POST /api/auth/logout — Invalidate session and lock vault if no other sessions active.
     */
    @PostMapping(value = "/logout", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> logout(@RequestHeader(TOKEN_HEADER) String token) {
        var session = sessionManager.validate(token);
        if (session.isEmpty()) {
            return ResponseEntity.status(401)
                    .body(Map.of("status", "error", "message", "Invalid or expired session"));
        }

        sessionManager.invalidate(token);

        if (sessionManager.activeSessionCount() == 0) {
            credentialVault.lock();
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

    // ── Status ──────────────────────────────────────────────────────────────

    /**
     * GET /api/auth/status — Returns vault and session state.
     */
    @GetMapping(value = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> status(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        boolean setupNeeded = !userRegistry.anyUserExists();
        boolean vaultLocked = !credentialVault.isUnlocked();

        var result = new LinkedHashMap<String, Object>();
        result.put("setupNeeded", setupNeeded);
        result.put("vaultLocked", vaultLocked);
        result.put("activeSessions", sessionManager.activeSessionCount());
        result.put("currentUser", credentialVault.getCurrentUser());
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

    /**
     * POST /api/auth/users — Add a new user with their own Vault Key. Requires admin session.
     */
    @PostMapping(
            value = "/users",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> addUser(
            @RequestHeader(TOKEN_HEADER) String token, @RequestBody Map<String, String> request) {

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

        if (username == null || username.isBlank() || password == null || password.isBlank()) {
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
            var newUser = userRegistry.create(username, password, role);

            // Generate a NEW per-user Vault Key for this user — never share admin's key
            SecretKey masterKey =
                    vaultKeyManager.deriveMasterKey(username, password, newUser.getPasswordSalt());
            SecretKey vaultKey = vaultKeyManager.generateVaultKey();
            vaultKeyManager.wrapVaultKey(vaultKey, masterKey, username);

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
