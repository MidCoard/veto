package top.focess.veto.terminal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import top.focess.veto.contract.TerminalRequest;
import top.focess.veto.contract.TerminalResponse;

/**
 * File-based IPC with the Veto backend.
 *
 * <h3>Request/Response Lifecycle</h3>
 *
 * <pre>
 * Terminal                              Backend
 * ────────                              ───────
 * send(req) →
 *   writes in/{id}.json ────────────→ TerminalChannel picks up
 *   polls out/{id}.json ←──────────── TerminalIO.respond() writes here
 *   deletes response file
 *   returns SendResult{response, id}
 *
 * if response.type == PROMPT:
 *   sendFollowUp(id, reply) →
 *     writes in/{id}-next.json ─────→ TerminalIO.readInput() polls this
 *     polls out/{id}.json ←────────── TerminalIO.respond() writes again
 *     deletes response file
 *     returns next response
 * </pre>
 */
public class FileChannel {

    private static final Path VAULT = Path.of(System.getProperty("user.home"), ".veto");
    private static final Path IN = VAULT.resolve("terminal/in");
    private static final Path OUT = VAULT.resolve("terminal/out");

    private final ObjectMapper json = new ObjectMapper();

    public record SendResult(TerminalResponse response, String requestId) {
    }

    /**
     * Magic prefix that signals a completion request to the backend.
     */
    private static final String COMPLETION_PREFIX = "\0complete:";

    /**
     * Ask the backend for Tab-completion candidates. Returns an empty list on timeout or error.
     *
     * @param partial      the partial input as typed by the user (e.g. "/pattern cr")
     * @param sessionToken current session token (nullable)
     * @param timeoutMs    how long to wait for the backend (keep short — this blocks the Tab handler)
     */
    public List<String> complete(String partial, String sessionToken, long timeoutMs) {
        SendResult sr =
                send(new TerminalRequest(COMPLETION_PREFIX + partial, sessionToken), timeoutMs);
        if (sr == null || sr.response() == null) return List.of();
        String content = sr.response().content();
        if (content == null || content.isBlank()) return List.of();
        return List.of(content.split("\n"));
    }

    /**
     * Send a new top-level request. Generates a random request ID, writes to {@code in/{id}.json},
     * polls {@code out/{id}.json} for the response.
     */
    public SendResult send(TerminalRequest request, long timeoutMs) {
        String id = UUID.randomUUID().toString();
        TerminalResponse resp = sendTo(id + ".json", id + ".json", request, timeoutMs);
        return new SendResult(resp, id);
    }

    /**
     * Follow-up to an existing request (after a PROMPT). Writes to {@code in/{requestId}-next.json}
     * so the backend's IOHandler picks it up, then polls {@code out/{requestId}.json} for the next
     * response.
     */
    public TerminalResponse sendFollowUp(
            String requestId, TerminalRequest request, long timeoutMs) {
        return sendTo(requestId + "-next.json", requestId + ".json", request, timeoutMs);
    }

    private TerminalResponse sendTo(
            String inFile, String outFile, TerminalRequest request, long timeoutMs) {
        try {
            Files.createDirectories(IN);
            Files.createDirectories(OUT);
            lockdown(IN);
            lockdown(OUT);

            Path reqFile = IN.resolve(inFile);
            Path respFile = OUT.resolve(outFile);

            json.writeValue(reqFile.toFile(), request);

            long deadline = System.currentTimeMillis() + timeoutMs;
            while (!Files.exists(respFile)) {
                if (System.currentTimeMillis() > deadline) {
                    try {
                        Files.deleteIfExists(reqFile);
                    } catch (IOException ignored) {
                    }
                    return null;
                }
                Thread.sleep(100);
            }

            TerminalResponse resp = json.readValue(respFile.toFile(), TerminalResponse.class);
            Files.delete(respFile);
            return resp;
        } catch (Exception e) {
            return null;
        }
    }

    public Path vaultHome() {
        return VAULT;
    }

    // ── File-permission lockdown ──────────────────────────────────────────────

    /**
     * Best-effort owner-only permissions on the directory and its vault-root ancestors. On POSIX
     * this means {@code 0700}; on Windows it replaces the ACL with owner-full-control only.
     */
    private static void lockdown(Path dir) {
        Path vaultRoot = VAULT;
        Path p = dir;
        while (p != null && p.startsWith(vaultRoot)) {
            try {
                if (Files.getFileStore(p).supportsFileAttributeView("posix")) {
                    Set<PosixFilePermission> perms =
                            Files.isDirectory(p)
                                    ? EnumSet.of(
                                    PosixFilePermission.OWNER_READ,
                                    PosixFilePermission.OWNER_WRITE,
                                    PosixFilePermission.OWNER_EXECUTE)
                                    : EnumSet.of(
                                    PosixFilePermission.OWNER_READ,
                                    PosixFilePermission.OWNER_WRITE);
                    Files.setPosixFilePermissions(p, perms);
                } else {
                    AclFileAttributeView acl =
                            Files.getFileAttributeView(p, AclFileAttributeView.class);
                    if (acl != null) {
                        var entry =
                                AclEntry.newBuilder()
                                        .setType(AclEntryType.ALLOW)
                                        .setPrincipal(acl.getOwner())
                                        .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                                        .build();
                        acl.setAcl(java.util.List.of(entry));
                    }
                }
            } catch (Exception ignored) {
                // best-effort — terminal must still work on restricted filesystems
            }
            if (p.equals(vaultRoot)) break;
            p = p.getParent();
        }
    }
}
