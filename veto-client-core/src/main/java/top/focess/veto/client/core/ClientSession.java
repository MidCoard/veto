package top.focess.veto.client.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.contract.IpcFrame;
import top.focess.veto.contract.IpcMeta;

/**
 * The shared client interaction protocol — the one piece both client applications used to
 * duplicate.
 *
 * <p>Owns session metadata (username, turn count, session id), the pending-request queue + awaiting
 * flag, the active server prompt, and the frame→event translation with dispatch-next-or-idle logic.
 * The two presentations (REPL, TUI) feed it user input ({@link #submit}, {@link #cancel}) and
 * inbound frames ({@link #onFrame}); it drives rendering back through {@link ClientView}.
 *
 * <h3>Thread model</h3>
 *
 * <p>Thread-safe via a <b>self-owned internal monitor</b> (not a borrowed external lock — that was
 * the {@code TerminalStatus} anti-pattern). The REPL touches it from two threads (the consumer
 * thread calling {@link #onFrame}, the main thread calling {@link #submit}/{@link #cancel}/snapshot
 * between blocking {@code readLine} calls); the TUI touches it single-threaded from its event loop.
 * View callbacks are collected under the lock and <b>fired outside it</b> (snapshot-then-emit), so
 * a view may re-enter the session without deadlock and the critical section stays short.
 *
 * <p>The session never sends frames itself — {@link #submit}/{@link #onFrame}/{@link #cancel}
 * <b>return</b> the {@link IpcFrame.ClientFrame} to send (or {@code null}); the caller owns the
 * {@link top.focess.veto.contract.IpcClient} and does the send. This keeps the core free of the
 * transport and avoids holding the monitor across a bounded-outbox {@code offer}.
 *
 * <h3>State machine</h3>
 *
 * <pre>
 *   IDLE ──submit(Request)──▶ AWAITING ──Prompt──▶ PROMPTED ──submit(Input)──▶ AWAITING
 *    ▲                           │                                         │
 *    └──── Done/Error (idle) ────┘◄──── Done/Error (dispatch next) ─────────┘
 * </pre>
 *
 * <p>Cancel from AWAITING/PROMPTED clears the queue and returns {@code Cancel} (state stays
 * AWAITING to await the backend's cancel-ack {@link IpcFrame.Done}); cancel from IDLE returns
 * {@code null} as a shutdown signal (nothing to cancel).
 */
public final class ClientSession {

    private static final Logger log = LoggerFactory.getLogger(ClientSession.class);

    /** Session interaction state — replaces both clients' bespoke state enums. */
    public enum State {
        IDLE,
        AWAITING_RESPONSE,
        PROMPTED
    }

    /** Immutable snapshot of session metadata for view rendering. */
    public record SessionMeta(
            @Nullable String username, int turnCount, @Nullable String sessionId) {}

    private final ClientView view;
    private final Object lock = new Object();
    private final Deque<String> pendingRequests = new ArrayDeque<>();

    private State state = State.IDLE;
    private IpcFrame.Prompt activePrompt;
    private String username;
    private int turnCount;
    private String sessionId;

    public ClientSession(@NotNull ClientView view) {
        this.view = view;
    }

    /**
     * User submitted a line.
     *
     * <p>{@code PROMPTED} → returns an {@link IpcFrame.Input} (prompt reply); {@code IDLE} →
     * enqueues and dispatches the first request, returning an {@link IpcFrame.Request}; {@code
     * AWAITING} → enqueues and returns {@code null} (dispatched when the in-flight request
     * completes).
     *
     * @param line the submitted line
     * @return the client frame to send, or {@code null} if only enqueued
     */
    @Nullable
    public IpcFrame.ClientFrame submit(@NotNull String line) {
        List<Runnable> events = new ArrayList<>();
        IpcFrame.ClientFrame frame;
        synchronized (lock) {
            if (state == State.PROMPTED) {
                activePrompt = null;
                state = State.AWAITING_RESPONSE;
                events.add(view::onAwaiting);
                frame = new IpcFrame.Input(line);
            } else {
                pendingRequests.addLast(line);
                if (state == State.IDLE) {
                    String first = pendingRequests.removeFirst();
                    state = State.AWAITING_RESPONSE;
                    events.add(view::onAwaiting);
                    frame = new IpcFrame.Request(first);
                } else {
                    frame = null; // enqueued; dispatched when the in-flight request completes
                }
            }
        }
        fire(events);
        return frame;
    }

    /**
     * Cancels the in-flight request / prompt. Clears the queue and returns a {@link
     * IpcFrame.Cancel} (state stays AWAITING to await the backend's cancel-ack Done). Returns
     * {@code null} when idle with an empty queue — a shutdown signal (the caller exits / enqueues a
     * shutdown event).
     *
     * @return the Cancel frame, or {@code null} to signal shutdown
     */
    @Nullable
    public IpcFrame.Cancel cancel() {
        List<Runnable> events = new ArrayList<>();
        IpcFrame.Cancel frame;
        synchronized (lock) {
            if (state == State.IDLE && pendingRequests.isEmpty()) {
                return null; // nothing to cancel — signal the caller to shut down
            }
            activePrompt = null;
            pendingRequests.clear();
            state = State.AWAITING_RESPONSE; // await the backend's cancel-ack Done
            events.add(view::onAwaiting);
            frame = new IpcFrame.Cancel();
        }
        fire(events);
        return frame;
    }

    /**
     * Processes an inbound server frame: updates state, applies metadata, dispatches the next
     * queued request or goes idle, and emits render events.
     *
     * @param frame the inbound frame
     * @return the client frame to send (a dispatched next {@link IpcFrame.Request}), or {@code
     *     null}
     */
    @Nullable
    public IpcFrame.ClientFrame onFrame(@NotNull IpcFrame.ServerFrame frame) {
        List<Runnable> events = new ArrayList<>();
        IpcFrame.ClientFrame reply = null;
        synchronized (lock) {
            switch (frame) {
                case IpcFrame.Delta d -> events.add(() -> view.onDelta(d.content()));
                case IpcFrame.Progress p ->
                        events.add(() -> view.onProgress(StyledText.muted("  ⏳ " + p.content())));
                case IpcFrame.Prompt pr -> {
                    activePrompt = pr;
                    state = State.PROMPTED;
                    events.add(view::onPrompted);
                    events.add(() -> view.onPrompt(pr));
                }
                case IpcFrame.Done done -> {
                    if (done.content() != null) {
                        events.add(() -> view.onDone(done.content()));
                    }
                    SessionMeta snap = applyMeta(done.meta());
                    if (snap != null) {
                        events.add(() -> view.onMetaChanged(snap));
                    }
                    reply = dispatchNextOrIdle(events);
                }
                case IpcFrame.Error e -> {
                    events.add(() -> view.onError(StyledText.error("Error: " + e.content())));
                    reply = dispatchNextOrIdle(events);
                }
                case IpcFrame.Terminate t -> {
                    String reason =
                            t.reason() != null ? t.reason() : "Server terminated the session.";
                    events.add(() -> view.onTerminate(StyledText.muted(reason)));
                }
                default ->
                        log.warn(
                                "Unexpected server frame {} — ignoring",
                                frame.getClass().getSimpleName());
            }
        }
        fire(events);
        return reply;
    }

    /**
     * Dispatches the next queued request (→ AWAITING) or goes IDLE. Must hold {@link #lock}.
     *
     * @param events the event list to append a state-transition signal to
     * @return the next Request to send, or {@code null} if idle
     */
    @Nullable
    private IpcFrame.ClientFrame dispatchNextOrIdle(@NotNull List<Runnable> events) {
        if (!pendingRequests.isEmpty()) {
            String next = pendingRequests.removeFirst();
            state = State.AWAITING_RESPONSE;
            events.add(view::onAwaiting);
            return new IpcFrame.Request(next);
        }
        state = State.IDLE;
        events.add(view::onIdle);
        return null;
    }

    /**
     * Applies session metadata from a {@link IpcFrame.Done} meta map using the typed {@link
     * IpcMeta} accessors (no hand-casts).
     *
     * @return a {@link SessionMeta} snapshot if anything changed (so the view can refresh), or
     *     {@code null} if unchanged
     */
    @Nullable
    private SessionMeta applyMeta(@NotNull Map<String, Object> meta) {
        boolean changed = false;
        if (meta.containsKey(IpcMeta.USERNAME)) {
            String u = IpcMeta.username(meta);
            if (!Objects.equals(u, username)) {
                username = u;
                changed = true;
            }
        }
        if (meta.containsKey(IpcMeta.TURN_NUMBER)) {
            int t = IpcMeta.turnNumber(meta, turnCount);
            if (t != turnCount) {
                turnCount = t;
                changed = true;
            }
        }
        if (meta.containsKey(IpcMeta.SESSION)) {
            String s = IpcMeta.session(meta);
            if (!Objects.equals(s, sessionId)) {
                sessionId = s;
                changed = true;
            }
        }
        if (IpcMeta.clearSession(meta)) {
            if (username != null || turnCount != 0 || sessionId != null) {
                username = null;
                turnCount = 0;
                sessionId = null;
                changed = true;
            }
        }
        return changed ? new SessionMeta(username, turnCount, sessionId) : null;
    }

    /**
     * Fires collected view callbacks outside the monitor; a throwing callback is logged, not
     * propagated.
     */
    private void fire(@NotNull List<Runnable> events) {
        for (Runnable r : events) {
            try {
                r.run();
            } catch (Exception e) {
                log.warn("View callback threw", e);
            }
        }
    }

    @NotNull
    public State state() {
        synchronized (lock) {
            return state;
        }
    }

    @Nullable
    public IpcFrame.Prompt activePrompt() {
        synchronized (lock) {
            return activePrompt;
        }
    }

    @NotNull
    public SessionMeta snapshot() {
        synchronized (lock) {
            return new SessionMeta(username, turnCount, sessionId);
        }
    }

    /** An immutable snapshot of the pending-request queue (for status-bar rendering). */
    @NotNull
    public List<String> pendingQueue() {
        synchronized (lock) {
            return List.copyOf(pendingRequests);
        }
    }
}
