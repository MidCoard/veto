package top.focess.veto.command;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.*;

import top.focess.command.IOHandler;
import top.focess.veto.contract.ResponseType;
import top.focess.veto.contract.TerminalResponse;

/**
 * IOHandler that owns the file channel. Uses the 2.1.0 library's built-in {@link #input(long)} for
 * blocking input and {@link #input(String)} for feeding replies. Structured responses are written
 * to the output file via {@link #respond(TerminalResponse)}.
 */
public class TerminalIO extends IOHandler {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path outDir;
    private final String requestId;
    private TerminalResponse lastResponse;
    private final StringBuilder log = new StringBuilder();

    public TerminalIO(Path outDir, String requestId) {
        this.outDir = outDir;
        this.requestId = requestId;
    }

    /**
     * Library standard: plain-text output (debug/status messages).
     */
    @Override
    public void output(String msg) {
        log.append(msg).append("\n");
    }

    /**
     * Write a structured response to the output file.
     */
    public void respond(TerminalResponse resp) {
        this.lastResponse = resp;
        try {
            Path file = outDir.resolve(requestId + ".json");
            JSON.writeValue(file.toFile(), resp);
        } catch (IOException e) {
            log.append("ERROR: ").append(e.getMessage());
        }
    }

    /**
     * The most recent response.
     */
    public TerminalResponse getResponse() {
        return lastResponse;
    }

    /**
     * Accumulated plain-text output.
     */
    public String getLog() {
        return log.toString();
    }

    /**
     * Convenience: respond with a simple error message.
     */
    public void error(String msg) {
        respond(new TerminalResponse(ResponseType.ERROR, msg));
    }

    /**
     * Convenience: respond with a simple message.
     */
    public void message(String msg) {
        respond(new TerminalResponse(ResponseType.MESSAGE, msg));
    }
}
