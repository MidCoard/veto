package top.focess.veto.client.core;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
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
 */
public interface ClientView {

    /** Streaming content chunk (plain text — styling is the view's job). */
    void onDelta(@NotNull String content);

    /**
     * Progress hint, already wrapped in a {@link StyledText} (typically {@link StyleToken#MUTED}).
     */
    void onProgress(@NotNull StyledText content);

    /** Backend requests input. The view should swap its prompt to the prompted shape. */
    void onPrompt(@NotNull IpcFrame.Prompt prompt);

    /** Terminal frame — request complete. {@code content} may be {@code null}. */
    void onDone(@Nullable String content);

    /**
     * Fatal error, already wrapped in a {@link StyledText} (typically {@link StyleToken#ERROR}).
     */
    void onError(@NotNull StyledText content);

    /** Server-forced termination, already wrapped in a {@link StyledText}. */
    void onTerminate(@NotNull StyledText content);

    /** State-transition signal: the session went idle (no in-flight or queued request). */
    default void onIdle() {}

    /** State-transition signal: the session is now awaiting a response (request dispatched). */
    default void onAwaiting() {}

    /**
     * State-transition signal: the session is now prompted (waiting for the user to answer a
     * Prompt).
     */
    default void onPrompted() {}

    /** Session metadata changed (username / turn count / session id, possibly cleared). */
    default void onMetaChanged(@NotNull ClientSession.SessionMeta meta) {}
}
