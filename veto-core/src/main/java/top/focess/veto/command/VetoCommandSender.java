package top.focess.veto.command;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.command.AbstractCommandSender;
import top.focess.command.CommandPermission;
import top.focess.command.CommandSender;
import top.focess.veto.agent.AgentRunner;
import top.focess.veto.agent.intercept.VetoPrompt;
import top.focess.veto.contract.IpcFrame;
import top.focess.veto.contract.Version;
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
 *       the command checks for null and decides how to proceed.
 *   <li><b>Running</b> — the command is executing (not awaiting input). Cancel aborts the entire
 *       in-flight request (the server calls {@code task.cancel(true)}).
 * </ol>
 *
 * <p>Whether the command is prompted is determined by {@link #isPrompted()}, which checks the
 * {@code inputFutures} queue inherited from {@link AbstractCommandSender} for an incomplete future.
 */
public final class VetoCommandSender extends AbstractCommandSender {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.command.VetoCommandSender");

    private final @NonNull IpcServer ipcServer;
    private volatile String username;
    private final @NonNull String terminalId;
    private final @NonNull Version clientProductVersion;

    /**
     * The current working directory the terminal reported in its {@link IpcFrame.Hello} handshake,
     * used as the session's workspace root when {@code /session create} does not name one
     * explicitly. Never {@code null} - the terminal always reports its JVM working dir.
     */
    private final @NonNull String cwd;

    /**
     * The pending HITL veto (agentId + callId), stashed when a veto Prompt is sent and claimed when
     * the user's reply (or a cancel) arrives. Under the 1:1 request invariant at most one veto is
     * pending per session, so a single slot suffices. Atomic: written on the agent virtual thread
     * (the veto sink), claimed on the session-worker thread (Input/Cancel).
     */
    private PendingVeto pendingVeto;

    /**
     * Constructs a new {@code VetoCommandSender} for the given terminal session.
     *
     * @param ipcServer the IPC server used to enqueue outbound frames
     * @param username the initially authenticated username, or {@code null} if not yet logged in
     * @param terminalId the ZMQ DEALER identity of the owning terminal
     * @param clientProductVersion the product version the connecting terminal reported in its
     *     {@link IpcFrame.Hello} handshake; never {@code null} - {@link Version#UNKNOWN} when the
     *     terminal did not report a meaningful version
     * @param cwd the current working directory the terminal reported in its {@link IpcFrame.Hello}
     *     handshake, mapped to the session's workspace at {@code /session create} time; never
     *     {@code null} - the terminal always reports its JVM working dir
     */
    public VetoCommandSender(
            @NonNull IpcServer ipcServer,
            String username,
            @NonNull String terminalId,
            @NonNull Version clientProductVersion,
            @NonNull String cwd) {
        super(CommandPermission.EVERYONE);
        this.ipcServer = ipcServer;
        this.username = username;
        this.terminalId = terminalId;
        this.clientProductVersion = clientProductVersion;
        this.cwd = cwd;
    }

    // ── identity ──────────────────────────────────────────────────────────

    /**
     * Returns the username of the authenticated user for this session.
     *
     * @return the username, or {@code null} if the terminal is not yet logged in
     */
    public String username() {
        return username;
    }

    /** Returns the authenticated username for code guarded by the logged-in permission. */
    public @NonNull String requireUsername() {
        String current = username;
        if (current == null) {
            throw new IllegalStateException("Command requires an authenticated user");
        }
        return current;
    }

    /**
     * Updates the authenticated username for this session.
     *
     * <p>Set to a non-null value after a successful login; reset to {@code null} on logout.
     *
     * @param username the new username, or {@code null} to mark the session as logged out
     */
    public void setUsername(String username) {
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
     * Returns the product version the connecting terminal reported in its {@link IpcFrame.Hello}
     * handshake.
     *
     * @return the terminal's product version; never {@code null} - {@link Version#UNKNOWN} when the
     *     terminal did not report a meaningful version
     */
    public @NonNull Version clientProductVersion() {
        return clientProductVersion;
    }

    /**
     * Returns the current working directory the connecting terminal reported in its {@link
     * IpcFrame.Hello} handshake, mapped to the session's workspace at {@code /session create} time.
     *
     * @return the terminal's cwd; never {@code null} - the terminal always reports its JVM working
     *     dir
     */
    public @NonNull String cwd() {
        return cwd;
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
    public void output(String message) {
        if (message == null || message.isEmpty()) return;
        ipcServer.send(terminalId, new IpcFrame.Delta(message));
    }

    /**
     * Sends a streaming <em>thought</em> chunk to the terminal as a thought-kind {@link
     * IpcFrame.Delta} frame. The terminal renders thoughts distinct (muted/dim) from user-facing
     * messages delivered via {@link #output}, so the user can follow the agent's reasoning without
     * it competing with the answer.
     *
     * <p>Null or empty thoughts are silently ignored.
     *
     * @param thought the interim reasoning text to stream; {@code null} or empty silently dropped
     */
    public void outputThought(String thought) {
        if (thought == null || thought.isEmpty()) return;
        ipcServer.send(terminalId, IpcFrame.Delta.thought(thought));
    }

    /**
     * Streams a tool call the agent is about to execute to the terminal as a {@link
     * IpcFrame.ToolCall} frame, so the terminal can render a Claude-Code-style indicator and the
     * user can see exactly which tool the agent invoked with which arguments. Receives the agent's
     * domain {@link AgentRunner.ToolCallEvent} and constructs the terminal wire frame HERE, at the
     * transport edge (the agent emits domain events and never builds an {@code IpcFrame}). Called
     * on the agent virtual thread after the TOOL_CALL turn has been durably persisted.
     */
    public void sendToolCall(AgentRunner.@NonNull ToolCallEvent call) {
        ipcServer.send(terminalId, new IpcFrame.ToolCall(call.toolName(), call.args()));
    }

    /**
     * Streams the framed observation the model receives for a tool call to the terminal as a {@link
     * IpcFrame.ToolResult} frame. The body is the self-describing "Observation (tool(args)) [...]"
     * text the model sees, so the terminal can render a single result and the user can verify which
     * call it belongs to without tracking call/result pairs. Receives the agent's domain {@link
     * AgentRunner.ToolResultEvent} and constructs the terminal wire frame here, at the transport
     * edge. Called on the agent virtual thread after the TOOL_RESPONSE turn has been durably
     * persisted.
     */
    public void sendToolResult(AgentRunner.@NonNull ToolResultEvent result) {
        ipcServer.send(terminalId, new IpcFrame.ToolResult(result.body(), result.success()));
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
    // focess-command declares this legacy override non-null, but cancellation is represented by
    // null in its runtime protocol. The nullable overload below is the actual Veto contract.
    @SuppressWarnings("override.return")
    @Override
    public String input() {
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
    public String input(@NonNull String text, boolean mask) {
        try {
            return inputAsync(text, mask, 90000).join();
        } catch (CancellationException e) {
            // join() throws a raw CancellationException (NOT wrapped in CompletionException) when
            // cancelCurrentPrompt() completes the input future with one - return null so the
            // command can handle the cancel (e.g. /login checks for null and returns REFUSE).
            return null;
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

    // ── HITL veto ────────────────────────────────────────────────────────

    /**
     * Sends a HITL veto prompt to the terminal as a {@link IpcFrame.Prompt} carrying a {@link
     * IpcFrame.VetoPayload}, and stashes the veto's (agentId, callId) so the inbound {@link
     * IpcFrame.Input} reply (or a {@link IpcFrame.Cancel}) can resolve it. Called from the agent's
     * veto emission seam (the veto sink), on the agent virtual thread.
     */
    public synchronized void sendVetoPrompt(@NonNull VetoPrompt vp) {
        pendingVeto = new PendingVeto(vp.agentId(), vp.callId());
        IpcFrame.VetoPayload payload =
                new IpcFrame.VetoPayload(
                        vp.agentId(),
                        vp.callId(),
                        vp.tool(),
                        vp.scenario().name(),
                        vp.options().stream().map(Enum::name).toList(),
                        vp.args());
        String summary = "HITL: " + vp.tool() + " (" + vp.scenario() + ")";
        ipcServer.send(terminalId, new IpcFrame.Prompt(summary, false, payload));
    }

    /**
     * Atomically claims the pending veto (returns and clears it), so the inbound handler resolves
     * it exactly once. Returns {@code null} if no veto is pending (a free-text prompt, or none).
     */
    public synchronized PendingVeto claimPendingVeto() {
        PendingVeto claimed = pendingVeto;
        pendingVeto = null;
        return claimed;
    }

    /** The (agentId, callId) stashed for a pending veto - the HitlRegistry key + call id. */
    public record PendingVeto(@NonNull String agentId, @NonNull String callId) {}
}
