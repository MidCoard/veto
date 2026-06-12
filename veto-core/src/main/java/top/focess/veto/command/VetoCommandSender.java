package top.focess.veto.command;

import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.command.AbstractCommandSender;
import top.focess.command.CommandPermission;
import top.focess.veto.contract.IpcFrame;
import top.focess.veto.contract.IpcMeta;
import top.focess.veto.contract.PromptMeta;
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

    @Nullable private final ZmqServer zmqServer;
    @Nullable private volatile String username;
    @NotNull private final String terminalId;
    private final Map<String, Object> doneMeta = new HashMap<>();
    private final Queue<String> inputBuffer = new ConcurrentLinkedQueue<>();

    private volatile boolean errorFlag;
    @Nullable private volatile String terminateReason;
    @NotNull private volatile String promptText = "Input:";
    @NotNull private volatile Map<String, Object> promptMeta = Map.of();

    public VetoCommandSender(
            @Nullable ZmqServer zmqServer, @Nullable String username, @NotNull String terminalId) {
        super(CommandPermission.EVERYONE);
        this.zmqServer = zmqServer;
        this.username = username;
        this.terminalId = terminalId;
    }

    @Override
    public boolean hasPermission(@NotNull CommandPermission permission) {
        if (permission == CommandPermission.EVERYONE) return true;
        return isLoggedIn();
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

    // ── dispatch state lifecycle ──────────────────

    public void resetForDispatch() {
        this.doneMeta.clear();
        this.errorFlag = false;
        this.terminateReason = null;
    }

    public void terminate(@Nullable String reason) {
        this.terminateReason = reason;
    }

    @Nullable
    public String terminateReason() {
        return terminateReason;
    }

    // ── output (CommandSender contract) ───────────────────────────────────

    @Override
    public void output(@Nullable String message) {
        if (message == null || message.isEmpty()) return;
        if (zmqServer != null) {
            log.info("output → outbox: {}", message.replace("\n", "\\n"));
            zmqServer.send(terminalId, new IpcFrame.Delta(message, 0));
        } else {
            log.warn("output dropped — zmqServer not wired (message={})", message);
        }
    }

    // ── async input (CommandSender contract) ──────────────────────────────

    /**
     * Sets the prompt text and metadata for the next {@link #input()} call. Use {@code
     * Map.of("prompt", "Password:", "mask", true)} for masked input.
     */
    public void setNextPromptMeta(@Nullable Map<String, Object> meta) {
        if (meta != null) {
            this.promptText = (String) meta.getOrDefault(IpcMeta.PROMPT, "Input:");
            this.promptMeta = meta;
        } else {
            this.promptText = "Input:";
            this.promptMeta = Map.of();
        }
    }

    /** Typed overload — prefer this over the raw {@code Map} variant. */
    public void setNextPromptMeta(@NotNull PromptMeta meta) {
        this.promptText = meta.text();
        this.promptMeta = meta.toMeta();
    }

    @Override
    @NotNull
    public CompletableFuture<String> inputAsync(long timeoutMillis) {
        // 1. Type-ahead buffer
        String buffered = inputBuffer.poll();
        if (buffered != null) {
            return CompletableFuture.completedFuture(buffered);
        }

        // 2. Push a Prompt frame so the terminal collects input
        if (zmqServer != null) {
            zmqServer.send(terminalId, new IpcFrame.Prompt(promptText, promptMeta));
        }

        // 3. Delegate — AbstractCommandSender creates future, queues it,
        //    schedules timeout. The IO thread delivers via receiveInput().
        return super.inputAsync(timeoutMillis);
    }

    // ── input buffer (type-ahead) ─────────────────────────────────────────

    public void bufferInput(@Nullable String input) {
        if (input != null && !input.isEmpty()) {
            inputBuffer.add(input);
        }
    }

    public void cancelPendingInput() {
        inputBuffer.clear();
    }

    // ── lifecycle state ───────────────────────────────────────────────────

    @NotNull
    public Map<String, Object> doneMeta() {
        return doneMeta;
    }

    public boolean hasError() {
        return errorFlag;
    }

    public void setErrorFlag() {
        this.errorFlag = true;
    }
}
