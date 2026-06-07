package top.focess.veto.command;

import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import top.focess.command.AbstractCommandSender;
import top.focess.command.CommandPermission;
import top.focess.veto.contract.IpcFrame;
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

    @Nullable private final String username;
    @NotNull private final String terminalId;
    private final Map<String, Object> doneMeta = new HashMap<>();
    private final Queue<String> inputBuffer = new ConcurrentLinkedQueue<>();
    @NotNull private volatile Function<String, String> sessionResolver = id -> null;

    private volatile boolean errorFlag;
    @Nullable private volatile Queue<ZmqServer.OutboxEntry> outbox;
    @Nullable private volatile String outboxIdentity;
    @NotNull private volatile String promptText = "Input:";
    @NotNull private volatile Map<String, Object> promptMeta = Map.of();

    public VetoCommandSender(@Nullable String username, @NotNull String terminalId) {
        super(CommandPermission.ADMINISTRATOR);
        this.username = username;
        this.terminalId = terminalId;
    }

    // ── identity ──────────────────────────────────────────────────────────

    @Nullable
    public String username() {
        String resolved = sessionResolver.apply(terminalId);
        return resolved != null ? resolved : username;
    }

    @NotNull
    public String terminalId() {
        return terminalId;
    }

    public boolean isLoggedIn() {
        return username() != null;
    }

    /** Wire the session resolver so isLoggedIn() checks live session state. */
    public void setSessionResolver(@NotNull Function<String, String> resolver) {
        this.sessionResolver = resolver;
    }

    // ── outbox wiring (called by ZmqServer per dispatch) ──────────────────

    public void setOutbox(@NotNull Queue<ZmqServer.OutboxEntry> outbox, @NotNull String identity) {
        this.outbox = outbox;
        this.outboxIdentity = identity;
        this.doneMeta.clear();
        this.errorFlag = false;
    }

    // ── output (CommandSender contract) ───────────────────────────────────

    @Override
    public void output(@Nullable String message) {
        if (message == null || message.isEmpty()) return;
        Queue<ZmqServer.OutboxEntry> q = this.outbox;
        String id = this.outboxIdentity;
        if (q != null && id != null) {
            q.add(new ZmqServer.OutboxEntry(id, new IpcFrame.Delta(message, 0)));
        }
    }

    // ── async input (CommandSender contract) ──────────────────────────────

    /**
     * Sets the prompt text and metadata for the next {@link #input()} call. Use {@code
     * Map.of("prompt", "Password:", "mask", true)} for masked input.
     */
    public void setNextPromptMeta(@Nullable Map<String, Object> meta) {
        if (meta != null) {
            this.promptText = (String) meta.getOrDefault("prompt", "Input:");
            this.promptMeta = meta;
        } else {
            this.promptText = "Input:";
            this.promptMeta = Map.of();
        }
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
        Queue<ZmqServer.OutboxEntry> q = this.outbox;
        String id = this.outboxIdentity;
        if (q != null && id != null) {
            q.add(new ZmqServer.OutboxEntry(id, new IpcFrame.Prompt(promptText, promptMeta)));
        }

        // 3. Delegate — AbstractCommandSender creates future, queues it,
        //    schedules timeout. The IO thread delivers via receiveInput().
        return super.inputAsync(timeoutMillis);
    }

    // ── input buffer (type-ahead) ─────────────────────────────────────────

    public void bufferInput(@Nullable String input) {
        if (input != null && !input.isBlank()) {
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
