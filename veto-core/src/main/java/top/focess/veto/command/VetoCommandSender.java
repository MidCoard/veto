package top.focess.veto.command;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.command.AbstractCommandSender;
import top.focess.command.CommandPermission;
import top.focess.veto.contract.IpcChannel;
import top.focess.veto.contract.IpcFrame;

/**
 * Veto command sender that doubles as the I/O handler. Holds the IPC channels directly so commands
 * can write streaming deltas, prompts, and terminal frames without a separate {@code TerminalIO}
 * object.
 */
public final class VetoCommandSender extends AbstractCommandSender {

    private static final Logger log = LoggerFactory.getLogger(VetoCommandSender.class);

    private final String username;
    private final String terminalId;
    private final IpcChannel reqChannel;
    private final IpcChannel respChannel;
    private final AtomicInteger deltaIndex = new AtomicInteger(0);

    private volatile boolean responded;
    private volatile boolean waitingForInput;

    public VetoCommandSender(
            String username, String terminalId, IpcChannel reqChannel, IpcChannel respChannel) {
        super(CommandPermission.ADMINISTRATOR);
        this.username = username;
        this.terminalId = terminalId;
        this.reqChannel = reqChannel;
        this.respChannel = respChannel;
    }

    // ── sender identity ────────────────────────────────────────────────

    public String getUsername() {
        return username;
    }

    public String terminalId() {
        return terminalId;
    }

    public boolean isLoggedIn() {
        return username != null;
    }

    // ── streaming output (CommandSender contract) ───────────────────────

    @Override
    public void output(String message) {
        delta(message);
    }

    @Override
    public String input() {
        return prompt("Input:", Map.of(), 60_000);
    }

    // ── frame-level I/O ─────────────────────────────────────────────────

    /** Write a streaming delta chunk. */
    public void delta(String content) {
        try {
            respChannel.send(new IpcFrame.Delta(content, deltaIndex.getAndIncrement()));
        } catch (IOException e) {
            log.warn("Failed to write delta", e);
        }
    }

    /** Write a progress indication. */
    public void progress(String content, int percent) {
        try {
            respChannel.send(new IpcFrame.Progress(content, percent));
        } catch (IOException e) {
            log.warn("Failed to write progress", e);
        }
    }

    /** Write a prompt and block for the user's reply. */
    public String prompt(String text, Map<String, Object> meta, long timeoutMs) {
        waitingForInput = true;
        try {
            respChannel.send(new IpcFrame.Prompt(text, meta));
            IpcFrame reply = reqChannel.receive(java.time.Duration.ofMillis(timeoutMs));
            if (reply instanceof IpcFrame.Input(String raw)) {
                return raw;
            }
            return null;
        } catch (IOException e) {
            return null;
        } finally {
            waitingForInput = false;
        }
    }

    /** Terminate the exchange with a done frame. */
    public void done(Map<String, Object> meta) {
        respond(new IpcFrame.Done(meta));
    }

    /** Terminate with a done frame carrying a plain-text message. */
    public void message(String msg) {
        respond(new IpcFrame.Done(Map.of(), msg));
    }

    /** Terminate the exchange with an error. */
    public void error(String msg) {
        respond(new IpcFrame.Error(msg));
    }

    /** Mark the exchange as responded. */
    public void respond(IpcFrame frame) {
        this.responded = true;
        try {
            respChannel.send(frame);
        } catch (IOException e) {
            log.error("Failed to send terminal frame", e);
        }
    }

    public boolean hasResponded() {
        return responded;
    }

    public boolean isWaitingForInput() {
        return waitingForInput;
    }
}
