package top.focess.veto.client.core;

import org.jspecify.annotations.NonNull;
import top.focess.veto.contract.IpcFrame;

/**
 * The presentation seam: {@link ClientSession} emits semantic render events through this interface,
 * and each client application implements it to render in its own style (REPL inline-above-prompt
 * via Mordant; TUI full-screen via JLine {@code Display}).
 *
 * <p>All methods are invoked <b>outside</b> the session's monitor, so an implementation may
 * re-enter the session (e.g. to read a snapshot) without deadlock. {@code default} methods let a
 * view implement only the events it renders; the TUI ignores the granular state signals (it redraws
 * the whole frame every event regardless).
 *
 * <p>Note on {@link IpcFrame.Done}: per the matrix a {@code Done} frame applies session meta (and
 * transitions state) but never displays content — command output streams via {@link #onDelta}. So
 * there is no {@code onDone(content)} event; a completed command is signalled by the {@link
 * #onRunning}/{@link #onIdle} transition and {@link #onMetaChanged}.
 */
public interface ClientView {

    /** Streaming content chunk (plain text — styling is the view's job). */
    void onDelta(@NonNull String content);

    /**
     * Streaming <em>thought</em> chunk - the agent's interim reasoning, as opposed to user-facing
     * message prose delivered via {@link #onDelta}. A view renders the two with different styles
     * (e.g. the thought dimmed/muted, the message as normal prose) so the user can follow the
     * agent's reasoning without it competing with the answer.
     *
     * <p>Default implementation ignores the thought, so views that predate this hook (or that
     * choose not to surface reasoning) still compile and behave as before.
     *
     * @param content the interim reasoning text
     */
    default void onThought(@NonNull String content) {}

    /**
     * A tool call the agent is about to execute (the agent's transparency emission seam). The
     * default implementation ignores the call, so views that predate this hook still compile and
     * behave as before. A view that renders the indicator should display it (typically muted) right
     * before the matching {@link #onToolResult} or user-facing message so the user can see exactly
     * which tool the agent is invoking with which arguments.
     */
    default void onToolCall(IpcFrame.@NonNull ToolCall call) {}

    /**
     * The framed observation the model received for a tool call (the agent's transparency emission
     * seam, paired with {@link #onToolCall}). The {@code body} is the self-describing "Observation
     * (tool(args)) [...]" text the model actually sees, so the view does not need to track
     * call/result pairs to render it.
     *
     * <p>Default implementation ignores the result, so views that predate this hook still compile
     * and behave as before.
     */
    default void onToolResult(IpcFrame.@NonNull ToolResult result) {}

    /**
     * Progress hint, already wrapped in a {@link StyledText} (typically {@link StyleToken#MUTED}).
     */
    void onProgress(@NonNull StyledText content);

    /** Backend requests input. The view should swap its prompt to the prompted shape. */
    void onPrompt(IpcFrame.@NonNull Prompt prompt);

    /**
     * Fatal error, already wrapped in a {@link StyledText} (typically {@link StyleToken#ERROR}).
     */
    void onError(@NonNull StyledText content);

    /** Server-forced termination, already wrapped in a {@link StyledText}. */
    void onTerminate(@NonNull StyledText content);

    /** State-transition signal: the session went idle (no in-flight or queued request). */
    default void onIdle() {}

    /** State-transition signal: the session is now running (a request dispatched / reply sent). */
    default void onRunning() {}

    /**
     * State-transition signal: the session is now prompted (waiting for the user to answer a
     * Prompt).
     */
    default void onPrompted() {}

    /**
     * A command's {@link IpcFrame.Request} was just dispatched — the line left the queue and is now
     * the in-flight command. Fires for <b>both</b> dispatch paths on the thread that performs the
     * dispatch: a line typed at IDLE (submitted from the calling thread) and the next queued
     * command auto-dispatched from {@code onFrame} when the in-flight command's terminal frame
     * resolves the queue (the consumer thread). Both paths echo uniformly here, so a queued command
     * is echoed <em>when it actually runs</em>, not when it was merely typed/enqueued — and an
     * enqueued command does not print "thinking…" before its turn.
     *
     * <p>Slash-commands ({@code /…}) are not echoed (they are echoed by JLine's history / the
     * prompt surface); only plain-text (agent) prompts are echoed with a "thinking…" marker.
     *
     * @param line the dispatched command line (already trimmed)
     */
    default void onCommandDispatched(@NonNull String line) {}

    /** Session metadata changed (username / turn count / session id, possibly cleared). */
    default void onMetaChanged(ClientSession.@NonNull SessionMeta meta) {}
}
