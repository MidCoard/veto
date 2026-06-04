package top.focess.veto.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import top.focess.command.IOHandler;
import top.focess.veto.contract.TerminalResponse;

/**
 * I/O bridge between the {@code focess-command} framework and the file-based terminal IPC.
 *
 * <p>Commands write responses via {@link #respond(TerminalResponse)} which serializes to the output
 * file. When a command needs user interaction (PROMPT), it calls {@link #input(long)} which blocks
 * until the terminal frontend supplies the follow-up via the file channel.
 */
public class TerminalIO extends IOHandler {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path outDir;
    private final String requestId;
    private TerminalResponse lastResponse;
    private volatile long lastActivityNanos;
    private volatile boolean responded;

    public TerminalIO(Path outDir, String requestId) {
        this.outDir = outDir;
        this.requestId = requestId;
        this.lastActivityNanos = System.nanoTime();
    }

    @Override
    public void output(String msg) {
    }

    /**
     * Write a structured response back to the terminal. The response is serialized to the output
     * file that the terminal's {@code FileChannel} is polling.
     *
     * <p>Sets the {@code responded} flag so the dispatch callback knows the command already wrote
     * its response and doesn't need to write again — preventing stale re-writes that corrupt the
     * PROMPT / follow-up protocol.
     */
    public void respond(TerminalResponse resp) {
        this.lastResponse = resp;
        this.responded = true;
        this.lastActivityNanos = System.nanoTime();
        try {
            JSON.writeValue(outDir.resolve(requestId + ".json").toFile(), resp);
        } catch (IOException ignored) {
        }
    }

    /**
     * True if the command has already written at least one response via {@link #respond}.
     */
    public boolean hasResponded() {
        return responded;
    }

    public TerminalResponse getResponse() {
        return lastResponse;
    }

    public void error(String msg) {
        respond(TerminalResponse.error(msg));
    }

    public void message(String msg) {
        respond(new TerminalResponse(top.focess.veto.contract.ResponseType.MESSAGE, msg));
    }

    /**
     * Returns true if no activity has been recorded on this I/O handler for longer than {@code
     * ttlMs}. Used by the terminal channel to clean up abandoned PROMPT interactions.
     */
    public boolean isStale(long ttlMs) {
        long elapsedMs = (System.nanoTime() - lastActivityNanos) / 1_000_000;
        return elapsedMs > ttlMs;
    }
}
