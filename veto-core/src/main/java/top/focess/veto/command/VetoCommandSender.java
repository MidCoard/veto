package top.focess.veto.command;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.command.AbstractCommandSender;
import top.focess.command.CommandPermission;
import top.focess.veto.contract.IpcFrame;
import top.focess.veto.contract.IpcMeta;
import top.focess.veto.terminal.ZmqServer;

/**
 * A pure {@link top.focess.command.CommandSender} for a single terminal session.
 *
 * <h3>Output</h3>
 *
 * {@link #output(String)} pushes {@code IpcFrame.Delta} entries onto the shared outbox queue. The
 * IO thread in {@link ZmqServer} drains the queue and sends frames on the ZMQ ROUTER socket.
 *
 * <h3>Input</h3>
 *
 * {@link #inputAsync(long)} first checks a type-ahead buffer. If empty it pushes a {@code Prompt}
 * frame onto the outbox so the terminal knows to collect input, then delegates to {@link
 * AbstractCommandSender#inputAsync(long)} which creates a {@link CompletableFuture} and queues it.
 * The IO thread calls {@link #receiveInput(String)} when an {@code Input} frame arrives —
 * completing the future and unblocking the dispatch worker. No extra threads are spawned.
 */
public final class VetoCommandSender extends AbstractCommandSender {

    private static final Logger log = LoggerFactory.getLogger(VetoCommandSender.class);

    @NotNull private final ZmqServer zmqServer;
    @Nullable private volatile String username;
    @NotNull private final String terminalId;

    public VetoCommandSender(
            @NotNull ZmqServer zmqServer, @Nullable String username, @NotNull String terminalId) {
        super(CommandPermission.EVERYONE);
        this.zmqServer = zmqServer;
        this.username = username;
        this.terminalId = terminalId;
    }

    // ── identity ──────────────────────────────────────────────────────────

    @Nullable
    public String username() {
        return username;
    }

    public void setUsername(@Nullable String username) {
        this.username = username;
    }

    @NotNull
    public String terminalId() {
        return terminalId;
    }

    public boolean isLoggedIn() {
        return username() != null;
    }

    // ── output (CommandSender contract) ───────────────────────────────────

    @Override
    public void output(@Nullable String message) {
        if (message == null || message.isEmpty()) return;
        log.info("output → outbox: {}", message.replace("\n", "\\n"));
        zmqServer.send(terminalId, new IpcFrame.Delta(message));
    }

    // ── input (CommandSender contract overrides & overloads) ──────────────────────────────

    @Override
    public @NotNull String input() {
        return input("", false);
    }

    public String input(@NotNull String text, boolean mask) {
        return inputAsync(text, mask, 90000).join();
    }

    @Override
    @NotNull
    public CompletableFuture<String> inputAsync(long timeoutMillis) {
        return inputAsync("", false, timeoutMillis);
    }

    @NotNull
    public CompletableFuture<String> inputAsync(
            @NotNull String text, boolean mask, long timeoutMillis) {
        zmqServer.send(
                terminalId,
                new IpcFrame.Prompt(text, Map.of(IpcMeta.PROMPT, text, IpcMeta.MASK, mask)));
        return super.inputAsync(timeoutMillis);
    }
}
