package top.focess.veto.command;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.command.AbstractCommandSender;
import top.focess.command.CommandPermission;
import top.focess.command.CommandSender;
import top.focess.veto.contract.IpcFrame;
import top.focess.veto.terminal.IpcServer;

/**
 * A pure {@link CommandSender} for a single terminal session.
 *
 * <h3>Output</h3>
 *
 * {@link #output(String)} pushes {@code IpcFrame.Delta} entries onto the shared outbox queue. The
 * IO thread in {@link IpcServer} drains the queue and sends frames on the ZMQ ROUTER socket.
 *
 * <h3>Input</h3>
 *
 * {@link #inputAsync(String, boolean, long)} sends a {@link IpcFrame.Prompt} frame so the terminal
 * knows to collect input, then creates a {@link CompletableFuture} (via {@link
 * AbstractCommandSender#inputAsync(long)}) and parks on it. The session worker calls {@link
 * #receiveInput(String)} when an {@link IpcFrame.Input} frame arrives — completing the future and
 * unblocking the dispatch worker. No extra threads are spawned.
 *
 * <h3>Cooperative cancel (IPC interaction )</h3>
 *
 * A {@link IpcFrame.Cancel} while a command is parked in {@code input} must not let the command
 * linger waiting out its timeout. {@link #cancelInput} completes the pending input future
 * exceptionally (a {@link CancellationException}), so {@code input.join} throws immediately and the
 * command unwinds — its terminal frame ({@code Done{cancelled}}, sent by the server's cancel
 * handler) resolves the {@link IpcFrame.Request}. The future is captured here because {@code
 * AbstractCommandSender}'s input-future queue is private.
 */
public final class VetoCommandSender extends AbstractCommandSender {

    private static final Logger log = LoggerFactory.getLogger(VetoCommandSender.class);

    private final @NonNull IpcServer ipcServer;
    private volatile @Nullable String username;
    private final @NonNull String terminalId;

    /**
     * The currently-pending input future (the one a command is parked in {@code input.join} on), or
     * {@code null} when no command awaits input. Captured so {@link #cancelInput} can complete it
     * exceptionally to cooperatively unblock a cancel. Under 1:1 there is at most one at a time.
     */
    private volatile @Nullable CompletableFuture<String> pendingInput;

    /**
     * Constructs a new {@code VetoCommandSender} for the given terminal session.
     *
     * @param ipcServer the IPC server used to enqueue outbound frames
     * @param username the initially authenticated username, or {@code null} if not yet logged in
     * @param terminalId the ZMQ DEALER identity of the owning terminal
     */
    public VetoCommandSender(
            @NonNull IpcServer ipcServer, @Nullable String username, @NonNull String terminalId) {
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
    public @Nullable String username() {
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
    public @NonNull String terminalId() {
        return terminalId;
    }

    /**
     * Returns whether this sender's session is currently authenticated.
     *
     * @return {@code true} if {@link #username} is non-null, {@code false} otherwise
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
    public @NonNull String input() {
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
    public String input(@NonNull String text, boolean mask) {
        return inputAsync(text, mask, 90000).join();
    }

    /**
     * Asynchronously requests user input with no prompt text and no masking.
     *
     * @param timeoutMillis maximum time to wait for a reply in milliseconds
     * @return a {@link CompletableFuture} that completes with the user's input string
     */
    @Override
    public @NonNull CompletableFuture<String> inputAsync(long timeoutMillis) {
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
    public @NonNull CompletableFuture<String> inputAsync(
            @NonNull String text, boolean mask, long timeoutMillis) {
        ipcServer.send(terminalId, new IpcFrame.Prompt(text, mask));
        CompletableFuture<String> future = super.inputAsync(timeoutMillis);
        pendingInput = future; // capture so cancelInput() can cooperatively unblock it
        return future;
    }

    /**
     * Cooperatively unblocks a command parked in {@code input} (IPC interaction ): completes the
     * pending input future exceptionally with a {@link CancellationException}, so {@code
     * input.join} throws immediately instead of waiting out its timeout. A no-op if no input is
     * pending (the command is not blocked in {@code input}).
     *
     * <p>The command's terminal frame ({@code Done{cancelled}}) is sent by the server's cancel
     * handler — this method only unblocks the parked input so the command unwinds.
     */
    public void cancelInput() {
        CompletableFuture<String> f = pendingInput;
        pendingInput = null;
        if (f != null && !f.isDone()) {
            f.completeExceptionally(new CancellationException("input cancelled by user"));
        }
    }
}
