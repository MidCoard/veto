package top.focess.veto.terminal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
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
}
