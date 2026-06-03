package top.focess.veto.terminal;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.file.*;
import java.util.UUID;

import top.focess.veto.contract.TerminalRequest;
import top.focess.veto.contract.TerminalResponse;

/**
 * Encapsulates all file-based IPC with the Veto backend.
 *
 * <p>Writes {@link TerminalRequest} JSON files to {@code ~/.veto/terminal/in/} and polls {@code
 * ~/.veto/terminal/out/} for the matching {@link TerminalResponse}.
 */
public class FileChannel {

    private static final Path VAULT_HOME = Path.of(System.getProperty("user.home"), ".veto");
    private static final Path IN_DIR = VAULT_HOME.resolve("terminal/in");
    private static final Path OUT_DIR = VAULT_HOME.resolve("terminal/out");

    private final ObjectMapper json;

    public FileChannel() {
        this.json = new ObjectMapper();
    }

    /**
     * Send a request and block until a response arrives or timeout.
     *
     * @param request   the request to send
     * @param timeoutMs maximum wait in milliseconds
     * @return the response, or null on timeout / I/O error
     */
    public TerminalResponse send(TerminalRequest request, long timeoutMs) {
        try {
            Files.createDirectories(IN_DIR);
            Files.createDirectories(OUT_DIR);

            String id = UUID.randomUUID().toString();
            Path reqFile = IN_DIR.resolve(id + ".json");
            Path respFile = OUT_DIR.resolve(id + ".json");

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

    /**
     * Expose the vault home for diagnostics.
     */
    public Path vaultHome() {
        return VAULT_HOME;
    }
}
