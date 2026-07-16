package top.focess.veto.command;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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
 * <h3>Two-level cancel</h3>
 *
 * A {@link IpcFrame.Cancel} has two levels:
 *
 * <ol>
 *   <li><b>Prompted</b> — the command is parked in {@code input} awaiting a reply. Cancel dismisses
 *       just the current prompt (via {@link #cancelCurrentPrompt()}); the command continues and may
 *       issue another prompt. The blocking {@code input} methods return {@code null} on cancel —
 *       the command checks for null and decides how to proceed. {@link CancelException} is retained
 *       only as a safety net: if a command calls {@code inputAsync().join()} directly without
 *       handling {@link CancellationException}, the registry catches it.
 *   <li><b>Running</b> — the command is executing (not awaiting input). Cancel aborts the entire
 *       in-flight request (the server calls {@code task.cancel(true)}).
 * </ol>
 *
 * <p>Whether the command is prompted is determined by {@link #isPrompted()}, which checks the
 * {@code inputFutures} queue inherited from {@link AbstractCommandSender} for an incomplete future.
 */
public final class VetoCommandSender extends AbstractCommandSender {

    private static final Logger log = LoggerFactory.getLogger(VetoCommandSender.class);

    private final @NonNull IpcServer ipcServer;
    private volatile @Nullable String username;
    private final @NonNull String terminalId;

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
     * @return the user's input string, or {@code null} if the prompt was cancelled
     */
    @Override
    public @Nullable String input() {
        return input("", false);
    }

    /**
     * Blocks until the terminal user provides input, optionally masking the characters.
     *
     * <p>Sends a {@link IpcFrame.Prompt} frame to the terminal with the given text and mask flag,
     * then waits up to 90 seconds for the terminal to respond with an {@link IpcFrame.Input} frame.
     * If the user cancels the prompt (via {@link IpcFrame.Cancel}), returns {@code null} instead of
     * throwing — the command checks for null and decides how to proceed (re-prompt, abort, etc.).
     *
     * @param text the prompt text to display above the input field; use {@code ""} for none
     * @param mask {@code true} to mask input characters (e.g. for passwords)
     * @return the user's input string, or {@code null} if the prompt was cancelled
     */
    public @Nullable String input(@NonNull String text, boolean mask) {
        try {
            return inputAsync(text, mask, 90000).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof CancellationException) {
                // Cancelled — return null so the command can handle it gracefully.
                return null;
            }
            throw e;
        }
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
        return super.inputAsync(timeoutMillis);
    }

    // ── cancel ───────────────────────────────────────────────────────────

    /**
     * Cancels the current prompt: completes the pending input future exceptionally with a {@link
     * CancellationException}, so {@code input.join} throws immediately instead of waiting out its
     * timeout. The command continues — it may issue another prompt or complete normally.
     *
     * <p>This is an atomic check-and-cancel: it returns {@code true} only if a prompt was actually
     * pending and was cancelled, avoiding the time-of-check-to-time-of-use race between checking
     * prompted state and calling cancel.
     *
     * @return {@code true} if a pending prompt was cancelled, {@code false} if no prompt was
     *     pending
     */
    public boolean cancelCurrentPrompt() {
        for (CompletableFuture<String> f : inputFutures) {
            if (!f.isDone()) {
                if (f.completeExceptionally(new CancellationException("input cancelled by user"))) {
                    log.debug("Cancelled current prompt");
                    return true;
                }
            }
        }
        return false;
    }

    // ── cancel-exception safety net ──────────────────────────────────────

    // CancelException is retained as a safety net: CommandRegistry catches it in
    // dispatchSlashCommand and converts it to Done{cancelled}. It should only escape
    // if a command calls inputAsync().join() directly without handling
    // CancellationException. The primary path is input() returning null on cancel.
}
