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
import top.focess.veto.terminal.IpcServer;

/**
 * A pure {@link top.focess.command.CommandSender} for a single terminal session.
 *
 * <h3>Output</h3>
 *
 * {@link #output(String)} pushes {@code IpcFrame.Delta} entries onto the shared outbox queue. The
 * IO thread in {@link IpcServer} drains the queue and sends frames on the ZMQ ROUTER socket.
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

    @NotNull private final IpcServer ipcServer;
    @Nullable private volatile String username;
    @NotNull private final String terminalId;

    /**
     * Constructs a new {@code VetoCommandSender} for the given terminal session.
     *
     * @param ipcServer the IPC server used to enqueue outbound frames
     * @param username the initially authenticated username, or {@code null} if not yet logged in
     * @param terminalId the ZMQ DEALER identity of the owning terminal
     */
    public VetoCommandSender(
            @NotNull IpcServer ipcServer, @Nullable String username, @NotNull String terminalId) {
        super(CommandPermission.EVERYONE);
        this.ipcServer = ipcServer;
        this.username = username;
        this.terminalId = terminalId;
    }

    // ── identity ──────────────────────────────────────────────────────────

    /**
     * Returns the username of the authenticated user for this session.
     *
     * @return the username, or {@code null} if the terminal is not yet logged in
     */
    @Nullable
    public String username() {
        return username;
    }

    /**
     * Updates the authenticated username for this session.
     *
     * <p>Set to a non-null value after a successful login; reset to {@code null} on logout.
     *
     * @param username the new username, or {@code null} to mark the session as logged out
     */
    public void setUsername(@Nullable String username) {
        this.username = username;
    }

    /**
     * Returns the ZMQ DEALER identity of the terminal that owns this sender.
     *
     * @return the terminal ID string; never {@code null}
     */
    @NotNull
    public String terminalId() {
        return terminalId;
    }

    /**
     * Returns whether this sender's session is currently authenticated.
     *
     * @return {@code true} if {@link #username()} is non-null, {@code false} otherwise
     */
    public boolean isLoggedIn() {
        return username() != null;
    }

    // ── output (CommandSender contract) ───────────────────────────────────

    /**
     * Sends a streaming content chunk to the terminal as a {@link IpcFrame.Delta} frame.
     *
     * <p>Null or empty messages are silently ignored. The frame is enqueued to the outbox of the
     * owning {@link IpcServer} and delivered by the IO thread.
     *
     * @param message the text chunk to stream; {@code null} or empty strings are silently dropped
     */
    @Override
    public void output(@Nullable String message) {
        if (message == null || message.isEmpty()) return;
        log.info("output → outbox: {}", message.replace("\n", "\\n"));
        ipcServer.send(terminalId, new IpcFrame.Delta(message));
    }

    // ── input (CommandSender contract overrides & overloads) ──────────────────────────────

    /**
     * Blocks until the terminal user provides input, with no prompt text and no masking.
     *
     * <p>Convenience override that delegates to {@link #input(String, boolean)} with a 90-second
     * timeout.
     *
     * @return the user's input string; never {@code null}
     */
    @Override
    public @NotNull String input() {
        return input("", false);
    }

    /**
     * Blocks until the terminal user provides input, optionally masking the characters.
     *
     * <p>Sends a {@link IpcFrame.Prompt} frame to the terminal with the given text and mask flag,
     * then waits up to 90 seconds for the terminal to respond with an {@link IpcFrame.Input} frame.
     *
     * @param text the prompt text to display above the input field; use {@code ""} for none
     * @param mask {@code true} to mask input characters (e.g. for passwords)
     * @return the user's input string; never {@code null}
     */
    public String input(@NotNull String text, boolean mask) {
        return inputAsync(text, mask, 90000).join();
    }

    /**
     * Asynchronously requests user input with no prompt text and no masking.
     *
     * @param timeoutMillis maximum time to wait for a reply in milliseconds
     * @return a {@link CompletableFuture} that completes with the user's input string
     */
    @Override
    @NotNull
    public CompletableFuture<String> inputAsync(long timeoutMillis) {
        return inputAsync("", false, timeoutMillis);
    }

    /**
     * Sends a {@link IpcFrame.Prompt} frame to the terminal and asynchronously waits for the user's
     * reply.
     *
     * <p>The future is completed by {@link #receiveInput(String)} when the session worker receives
     * the corresponding {@link IpcFrame.Input} frame from the terminal.
     *
     * @param text the prompt message displayed above the input field
     * @param mask {@code true} to mask input characters (e.g. for passwords)
     * @param timeoutMillis maximum time to wait in milliseconds before the future times out
     * @return a {@link CompletableFuture} that completes with the user's reply
     */
    @NotNull
    public CompletableFuture<String> inputAsync(
            @NotNull String text, boolean mask, long timeoutMillis) {
        ipcServer.send(
                terminalId,
                new IpcFrame.Prompt(text, Map.of(IpcMeta.PROMPT, text, IpcMeta.MASK, mask)));
        return super.inputAsync(timeoutMillis);
    }
}
