package top.focess.veto.controller;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import top.focess.veto.agent.workspace.WorkspaceAdmissionPolicy;
import top.focess.veto.controller.dto.*;
import top.focess.veto.i18n.Msg;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.session.SessionService;
import top.focess.veto.vault.KeysteadVault;

/**
 * Directory browsing and explicit child-directory creation for remote UIs (veto-ui), so a session's
 * workspace roots can be picked from the server's filesystem instead of typed blind. Directories
 * only - a workspace root is always a directory, and not listing files keeps the surface (and
 * response sizes) small. Hidden and unreadable entries are skipped.
 *
 * <p>Deployment policy and persisted workspace ownership constrain every operation. Tenant clients
 * see logical paths within their owner mapping; host base paths are never returned.
 */
@RestController
@RequestMapping("/api/fs")
public class FsController {

    private final @NonNull KeysteadVault vault;
    private final @NonNull WorkspaceAdmissionPolicy policy;
    private final @NonNull SessionRepository sessions;
    private final @NonNull SessionService service;

    /** Creates the controller with the vault used to check authentication. */
    public FsController(
            @NonNull KeysteadVault vault,
            @NonNull WorkspaceAdmissionPolicy policy,
            @NonNull SessionRepository sessions,
            @NonNull SessionService service) {
        this.vault = vault;
        this.policy = policy;
        this.sessions = sessions;
        this.service = service;
    }

    /**
     * GET /api/fs/browse - filesystem roots (drive letters on Windows, {@code /} on Unix). GET
     * /api/fs/browse?path=... - the subdirectories of {@code path}. Response: {@code {path, parent,
     * entries: [{name, path}]}}, with {@code path}/{@code parent} null at the root level.
     */
    @GetMapping("/browse")
    public @NonNull ResponseEntity<?> browse(@RequestParam(required = false) String path) {
        String user = vault.currentUser();
        if (user == null) {
            return ResponseEntity.status(401)
                    .body(new ErrorResponse(Msg.get("error.auth.notAuthenticated")));
        }
        var occupied = sessions.claimedRootsExcept(user);
        if (path == null || path.isBlank()) {
            List<DirectoryEntryResponse> roots = new ArrayList<>();
            for (Path root : policy.browseBases(user)) {
                if (policy.canAccess(user, root, occupied)) {
                    var text = policy.toClientPath(user, root);
                    roots.add(
                            new DirectoryEntryResponse(
                                    policy.tenant() ? "Workspace " + (roots.size() + 1) : text,
                                    text,
                                    false,
                                    policy.canSelect(user, root, occupied),
                                    true));
                }
            }
            return ResponseEntity.ok(new DirectoryListingResponse(null, null, roots, false, false));
        }
        Path dir;
        try {
            // absoluteNormalized rejects traversal syntax; toRealPath resolves symlinks before use.
            //noinspection tainting
            dir = policy.fromClientPath(user, path);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, Msg.get("error.fs.notDirectory", path));
        }
        if (!policy.canBrowse(user, dir, occupied))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Workspace access denied");
        if (policy.tenant() && !Files.exists(dir)) {
            return ResponseEntity.ok(
                    new DirectoryListingResponse(path, null, List.of(), false, true));
        }
        if (!Files.isDirectory(dir)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, Msg.get("error.fs.notDirectory", path));
        }
        List<DirectoryEntryResponse> entries = new ArrayList<>();
        try (Stream<Path> children = Files.list(dir)) {
            children.filter(Files::isDirectory)
                    // Dotfiles and Windows system dirs ($RECYCLE.BIN, System Volume Information)
                    .filter(
                            child -> {
                                String name = fileName(child);
                                return !name.startsWith(".") && !name.startsWith("$");
                            })
                    .filter(Files::isReadable)
                    .sorted(Comparator.comparing(child -> fileName(child).toLowerCase()))
                    .forEach(
                            child -> {
                                Path canonical;
                                try {
                                    canonical = child.toRealPath();
                                } catch (IOException e) {
                                    return;
                                }
                                if (!policy.canAccess(user, canonical, List.of())) return;
                                if (occupied.stream()
                                        .anyMatch(
                                                claim ->
                                                        canonical.startsWith(claim)
                                                                && !canonical.equals(claim)))
                                    return;
                                var declared = occupied.contains(canonical);
                                var text = policy.toClientPath(user, canonical);
                                entries.add(
                                        new DirectoryEntryResponse(
                                                fileName(child),
                                                text,
                                                declared,
                                                policy.canSelect(user, canonical, occupied),
                                                policy.canBrowse(user, canonical, occupied)));
                            });
        } catch (IOException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    Msg.get("error.fs.cannotList", path, "Directory cannot be listed"));
        }
        Path parent = dir.getParent();
        return ResponseEntity.ok(
                new DirectoryListingResponse(
                        policy.toClientPath(user, dir),
                        parent != null && policy.canBrowse(user, parent, occupied)
                                ? policy.toClientPath(user, parent)
                                : null,
                        entries,
                        policy.canSelect(user, dir, occupied),
                        true));
    }

    /** Directory to create: the existing {@code parent} path and the new child {@code name}. */
    public record CreateDirectoryRequest(String parent, String name) {}

    /**
     * Creates a single child directory under an existing, readable parent. Validates the name
     * against portable filename rules; 409 when it already exists, 400 when the parent is invalid
     * or creation fails. Returns 201 with the created path.
     */
    @PostMapping("/directories")
    public @NonNull ResponseEntity<?> createDirectory(
            @RequestBody @NonNull CreateDirectoryRequest request) {
        var user = vault.currentUser();
        if (user == null) {
            return ResponseEntity.status(401)
                    .body(new ErrorResponse(Msg.get("error.auth.notAuthenticated")));
        }
        String parentText = request.parent();
        String name = request.name();
        if (name == null
                || name.isBlank()
                || name.length() > 255
                || !name.equals(name.strip())
                || name.endsWith(".")
                || name.chars().anyMatch(c -> c < 32 || "/\\:<>\"|?*,".indexOf(c) >= 0)) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse(Msg.get("error.fs.invalidName")));
        }
        Path parent;
        try {
            if (parentText == null) throw new IllegalArgumentException("Missing parent");
            parent = policy.fromClientPath(user, parentText);
            if (!Files.isDirectory(parent)
                    && !(policy.tenant() && policy.browseBases(user).contains(parent)))
                throw new IllegalArgumentException("Not a directory");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(
                            new ErrorResponse(
                                    Msg.get(
                                            "error.fs.notDirectory",
                                            parentText == null ? "" : parentText)));
        }
        var occupied = sessions.claimedRootsExcept(user);
        if (!policy.canBrowse(user, parent, occupied)
                || !policy.canSelect(user, parent.resolve(name), occupied))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Workspace access denied");
        try {
            Path created = service.createWorkspaceDirectory(user, parent, name);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(
                            new DirectoryCreatedResponse(
                                    policy.toClientPath(user, created),
                                    policy.canBrowse(user, created, occupied)));
        } catch (FileAlreadyExistsException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse(Msg.get("error.fs.alreadyExists", name)));
        } catch (IOException | IllegalArgumentException | SecurityException e) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse(Msg.get("error.fs.cannotCreate", name)));
        }
    }

    private static @NonNull String fileName(@NonNull Path path) {
        Path fileName = path.getFileName();
        return fileName == null ? path.toString() : fileName.toString();
    }
}
