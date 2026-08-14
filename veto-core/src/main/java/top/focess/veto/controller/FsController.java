package top.focess.veto.controller;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import top.focess.veto.i18n.Msg;
import top.focess.veto.security.HostPathInput;
import top.focess.veto.vault.KeysteadVault;

/**
 * Read-only directory listing for remote UIs (veto-ui), so a session's workspace roots can be
 * picked from the server's filesystem instead of typed blind. Directories only - a workspace root
 * is always a directory, and not listing files keeps the surface (and response sizes) small. Hidden
 * and unreadable entries are skipped.
 *
 * <p>Any authenticated user may browse: sessions already accept arbitrary host paths, so this
 * exposes nothing the create endpoint would not. There is no allowlist - deployment policy does not
 * restrict which directories a session may bind to.
 */
@RestController
@RequestMapping("/api/fs")
public class FsController {

    private final @NonNull KeysteadVault vault;

    public FsController(@NonNull KeysteadVault vault) {
        this.vault = vault;
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
                    .body(Map.of("error", Msg.get("error.auth.notAuthenticated")));
        }
        if (path == null || path.isBlank()) {
            List<Map<String, String>> roots = new ArrayList<>();
            for (Path root : FileSystems.getDefault().getRootDirectories()) {
                String text = root.toString();
                roots.add(Map.of("name", text, "path", text));
            }
            return ResponseEntity.ok(body(null, null, roots));
        }
        Path dir;
        try {
            // absoluteNormalized rejects traversal syntax; toRealPath resolves symlinks before use.
            //noinspection tainting
            dir = HostPathInput.absoluteNormalized(path, "path").toRealPath();
        } catch (IllegalArgumentException | IOException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, Msg.get("error.fs.notDirectory", path));
        }
        if (!Files.isDirectory(dir)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, Msg.get("error.fs.notDirectory", path));
        }
        List<Map<String, String>> entries = new ArrayList<>();
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
                            child ->
                                    entries.add(
                                            Map.of(
                                                    "name",
                                                    fileName(child),
                                                    "path",
                                                    child.toString())));
        } catch (IOException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    Msg.get("error.fs.cannotList", dir, String.valueOf(e.getMessage())));
        }
        Path parent = dir.getParent();
        return ResponseEntity.ok(
                body(dir.toString(), parent != null ? parent.toString() : null, entries));
    }

    private static @NonNull Map<String, Object> body(
            String path, String parent, @NonNull List<Map<String, String>> entries) {
        // LinkedHashMap: Map.of rejects the null path/parent of the root listing.
        Map<String, Object> body = new LinkedHashMap<>();
        if (path != null) {
            body.put("path", path);
        }
        if (parent != null) {
            body.put("parent", parent);
        }
        body.put("entries", entries);
        return body;
    }

    private static @NonNull String fileName(@NonNull Path path) {
        Path fileName = path.getFileName();
        return fileName == null ? path.toString() : fileName.toString();
    }
}
