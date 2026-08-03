package top.focess.veto.controller;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.session.SessionService;
import top.focess.veto.vault.KeysteadVault;

/**
 * REST facade over {@link SessionService} for remote UIs (veto-ui).
 *
 * <p>Unlike the terminal path - which maps the terminal's cwd to the workspace via the {@link
 * top.focess.veto.contract.IpcFrame.Hello} handshake - a remote UI has no cwd to report, so it
 * declares the workspace roots explicitly in the create request body. The owner is the
 * authenticated vault user; activation/attachment is a UI concern (the UI holds the returned
 * session id and submits prompts through its own transport), so this controller only manages the
 * session lifecycle, not prompt dispatch.
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final @NonNull SessionService service;
    private final @NonNull KeysteadVault vault;

    public
    @NonNull
    SessionController(@NonNull SessionService service, @NonNull KeysteadVault vault) {
        this.service = service;
        this.vault = vault;
    }

    @GetMapping
    public @NonNull List<SessionEntity> list() {
        String user = vault.currentUser();
        return user != null ? service.listSessions(user) : List.of();
    }

    /**
     * Creates a session from a pattern, declaring its workspace roots.
     *
     * @param body {@code pattern} and {@code workspaceRoots} (CSV, multi-root) are both required;
     *     {@code name} is optional. A remote UI has no cwd to report, so it must name the workspace
     *     roots explicitly - the request is rejected with 400 when either is missing/blank.
     */
    @PostMapping
    public @NonNull SessionEntity create(@NonNull @RequestBody CreateSessionRequest body) {
        String user = vault.currentUser();
        if (user == null) throw new IllegalStateException("Not logged in");
        String pattern = body.pattern();
        String roots = body.workspaceRoots();
        if (pattern == null || pattern.isBlank() || roots == null || roots.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "pattern and workspaceRoots are both required");
        }
        return service.createSession(user, pattern, body.name(), roots);
    }

    @DeleteMapping("/{name}")
    public @NonNull ResponseEntity<Void> delete(@NonNull @PathVariable String name) {
        String user = vault.currentUser();
        if (user == null) return ResponseEntity.status(401).build();
        if (!service.delete(user, name)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found: " + name);
        }
        return ResponseEntity.noContent().build();
    }

    /** Request body for {@link #create}. */
    public record CreateSessionRequest(
            @NonNull String pattern, @Nullable String name, @NonNull String workspaceRoots) {}
}
