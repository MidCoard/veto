package top.focess.veto.client.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.contract.IpcClient;
import top.focess.veto.contract.IpcFrame;
import top.focess.veto.contract.IpcMeta;

/**
 * The shared client interaction protocol — the closed-loop state machine from the IPC interaction.
 * Both client applications feed it user input ({@link #submit}, {@link #cancel}) and inbound frames
 * ({@link #onFrame}); it drives rendering back through {@link ClientView}.
 *
 * <h3>States </h3>
 *
 * <ul>
 *   <li><b>{@code IDLE}</b> — no command in flight.
 *   <li><b>{@code RUNNING}</b> — one command in flight; streaming and terminal frames may arrive.
 *   <li><b>{@code PROMPTED}</b> — one command in flight <em>and</em> the backend awaits an {@link
 *       IpcFrame.Input}.
 * </ul>
 *
 * <p>The inbound table is a <b>state × frame</b> matrix — every cell is defined. At {@code IDLE},
 * streaming frames ({@link IpcFrame.Delta}/{@link IpcFrame.Progress}/{@link IpcFrame.Prompt}) are
 * <b>rejected + logged</b> (an orphan: under 1:1 a streaming frame belongs to the in-flight
 * command, and at {@code IDLE} none is in flight); the terminal frames ({@link
 * IpcFrame.Done}/{@link IpcFrame.Error}) are tolerated (a cancel racing a normal completion can
 * deliver a duplicate terminal frame) — {@code Done} applies meta, {@code Error} displays, and the
 * state stays {@code IDLE}. {@link IpcFrame.Terminate} tears down from any state.
 *
 * <h3>Thread model</h3>
 *
 * <p>Thread-safe via a <b>self-owned internal monitor</b>. The REPL touches it from two threads
 * (the consumer thread calling {@link #onFrame}, the main thread calling {@link #submit}/{@link
 * #cancel}/snapshot between blocking {@code readLine} calls); the TUI touches it single-threaded
 * from its event loop. View callbacks are collected under the lock and <b>fired outside it</b>
 * (snapshot-then-emit), so a view may re-enter the session without deadlock and the critical
 * section stays short.
 *
 * <p>The session never sends frames itself — {@link #submit}/{@link #onFrame}/{@link #cancel}
 * <b>return</b> the {@link IpcFrame.ClientFrame} to send (or {@code null}); the caller owns the
 * {@link IpcClient} and does the send. This keeps the core free of the transport and avoids holding
 * the monitor across a bounded-outbox {@code offer}.
 *
 * <h3>Routing on the authoritative atomic op </h3>
 *
 * <p>{@link #submit} routes a line on its own return — it checks state and acts under one lock, so
 * the returned frame is the source of truth (Input / Request / null=discard). A snapshot captured
 * before a blocking read is for rendering only; a line typed for a {@code Prompt} that already
 * resolved (a terminal frame raced the reply) is discarded by {@link #submit} returning {@code
 * null} — never repurposed as a new command (the stale-reply rule).
 */
public final class ClientSession {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.client.core.ClientSession");

    /** Session interaction state — IDLE / RUNNING / PROMPTED. */
    public enum State {
        IDLE,
        RUNNING,
        PROMPTED
    }

    /** Immutable snapshot of session metadata for view rendering. */
    public record SessionMeta(String username, int turnCount, String sessionId) {}

    /**
     * Immutable snapshot of everything the prompt renderer needs at one instant: the interaction
     * state, the active server prompt (when {@link State#PROMPTED}), and the username (to pick the
     * logged-in/out prompt marker).
     *
     * <p>Captured atomically by {@link #promptView} so the three fields describe a single
     * consistent moment. Reading them via separate {@code state} / {@code activePrompt} calls would
     * be a time-of-check/time-of-use race — the consumer thread can transition the state machine
     * (e.g. a {@link IpcFrame.Prompt} arriving) between the reads, so the renderer could act on a
     * {@code state} and an {@code activePrompt} that never coexisted.
     */
    public record PromptView(@NonNull State state, IpcFrame.Prompt activePrompt, String username) {}

    /**
     * Immutable snapshot of everything the status bar renders at one instant: session metadata and
     * the pending-request queue.
     *
     * <p>Captured atomically by {@link #statusView} so the username and the queue describe the same
     * moment. Reading them via separate {@code snapshot} / {@code pendingQueue} calls would let the
     * consumer thread mutate the session between the reads (a {@link IpcFrame.Done} changing the
     * username, or a {@code submit} adding to the queue), so the bar could show a username and a
     * queue that never coexisted.
     */
    public record StatusView(String username, int turnCount, @NonNull List<String> pending) {}

    private final @NonNull ClientView view;
    private final @NonNull Object lock = new Object();
    private final @NonNull Deque<@NonNull String> pendingRequests = new ArrayDeque<>();

    private @NonNull State state = State.IDLE;
    private IpcFrame.Prompt activePrompt;
    private String username;
    private int turnCount;
    private String sessionId;

    public ClientSession(@NonNull ClientView view) {
        this.view = view;
    }

    /**
     * User submitted a line ( outbound). Routes on the live state under one lock and returns the
     * frame to send:
     *
     * <ul>
     *   <li>{@code PROMPTED} → send {@link IpcFrame.Input} (prompt reply); → {@code RUNNING}.
     *   <li>{@code IDLE} → dispatch the first request, returning a {@link IpcFrame.Request}; →
     *       {@code RUNNING}.
     *   <li>{@code RUNNING} → enqueue; return {@code null} (dispatched when the in-flight request
     *       completes).
     * </ul>
     *
     * <p><b>Stale-reply rule :</b> a line is only an {@code Input} while the state is {@code
     * PROMPTED} at the moment of this call. If a terminal frame resolved the prompt between the
     * render snapshot and this call, the state is no longer {@code PROMPTED} and the line is
     * treated as a new command (or enqueued) — it is never silently sent as a stale {@code Input}.
     *
     * @param line the submitted line
     * @return the client frame to send, or {@code null} if only enqueued
     */
    public IpcFrame.ClientFrame submit(@NonNull String line) {
        List<Runnable> events = new ArrayList<>();
        IpcFrame.ClientFrame frame;
        synchronized (lock) {
            if (state == State.PROMPTED) {
                activePrompt = null;
                state = State.RUNNING;
                events.add(view::onRunning);
                frame = new IpcFrame.Input(line);
            } else {
                pendingRequests.addLast(line);
                if (state == State.IDLE) {
                    String first = pendingRequests.removeFirst();
                    state = State.RUNNING;
                    events.add(view::onRunning);
                    events.add(() -> view.onCommandDispatched(first));
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
     * Cancels the in-flight command. Returns a {@link IpcFrame.Cancel}; the state stays {@code
     * RUNNING} awaiting the command's terminal frame (the real {@link IpcFrame.Done}/{@link
     * IpcFrame.Error}, or {@code Done{cancelled}} if the cancel produced one), after which
     * dispatch-next-or-idle runs the next queued request or goes {@code IDLE}.
     *
     * <p>The pending-request queue is <b>preserved</b> (: dispatch-next-or-idle may still dispatch
     * a queued request after the cancelled command's terminal frame) — cancelling one command does
     * not drop the rest of the queue. A {@code PROMPTED} prompt is cleared.
     *
     * <p>Returns {@code null} when idle with an empty queue — a shutdown signal ( IDLE × cancel =
     * shutdown); the caller exits / enqueues a shutdown event.
     *
     * @return the Cancel frame, or {@code null} to signal shutdown
     */
    public IpcFrame.Cancel cancel() {
        List<Runnable> events = new ArrayList<>();
        IpcFrame.Cancel frame;
        synchronized (lock) {
            if (state == State.IDLE) {
                return null; // nothing to cancel — signal the caller to shut down
            }
            boolean wasPrompted = state == State.PROMPTED;
            activePrompt = null;
            state = State.RUNNING; // await the command's terminal frame
            if (wasPrompted) {
                events.add(view::onRunning);
            }
            frame = new IpcFrame.Cancel();
        }
        fire(events);
        return frame;
    }

    /**
     * Processes an inbound server frame per the state × frame matrix: updates state, applies
     * metadata, dispatches the next queued request or goes idle, and emits render events.
     *
     * <p>{@link IpcFrame.Done} applies meta (and transitions) but never displays content — command
     * output streams via {@link IpcFrame.Delta}; {@code Done} is the terminal marker only. {@link
     * IpcFrame.Error} displays and transitions. At {@code IDLE}, streaming frames are rejected +
     * logged rather than displayed.
     *
     * @param frame the inbound frame
     * @return the client frame to send (a dispatched next {@link IpcFrame.Request}), or {@code
     *     null}
     */
    @SuppressWarnings("EmptyStatementBody")
    public IpcFrame.ClientFrame onFrame(IpcFrame.@NonNull ServerFrame frame) {
        List<Runnable> events = new ArrayList<>();
        IpcFrame.ClientFrame reply = null;
        synchronized (lock) {
            switch (frame) {
                // ── streaming ───────────────────────────────────────────────────
                case IpcFrame.Delta d -> {
                    if (state == State.IDLE) {
                        rejectOrphan(d, "Delta");
                    } else if (d.isThought()) {
                        // Interim reasoning is routed to a separate view hook so the renderer can
                        // style it distinct from user-facing message prose.
                        events.add(() -> view.onThought(d.content()));
                    } else {
                        events.add(() -> view.onDelta(d.content()));
                    }
                }
                case IpcFrame.Progress p -> {
                    if (state == State.IDLE) {
                        rejectOrphan(p, "Progress");
                    } else {
                        events.add(() -> view.onProgress(StyledText.muted("  ⏳ " + p.content())));
                    }
                }
                case IpcFrame.ToolCall tc -> {
                    // Transparency emission: the agent is about to execute a tool call. Routed to
                    // the view so a renderer can show a Claude-Code-style indicator. An orphan
                    // call (no in-flight request) is dropped silently — the audit log still
                    // records the call durably on the backend, so the user is not misled.
                    if (state != State.IDLE) {
                        events.add(() -> view.onToolCall(tc));
                    }
                }
                case IpcFrame.ToolResult tr -> {
                    // Transparency emission: the framed observation the model received. The body is
                    // self-describing (carries the tool + args in its "Observation (...)" header),
                    // so the view does not need to track call/result pairs to render it. Same
                    // orphan tolerance as ToolCall.
                    if (state != State.IDLE) {
                        events.add(() -> view.onToolResult(tr));
                    }
                }
                case IpcFrame.Prompt pr -> {
                    switch (state) {
                        case IDLE -> rejectOrphan(pr, "Prompt");
                        case RUNNING -> {
                            activePrompt = pr;
                            state = State.PROMPTED;
                            events.add(view::onPrompted);
                            events.add(() -> view.onPrompt(pr));
                        }
                        case PROMPTED -> {
                            activePrompt = pr; // replace the outstanding prompt
                            events.add(() -> view.onPrompt(pr));
                        }
                    }
                }

                // ── terminal ────────────────────────────────────────────────────
                case IpcFrame.Done done -> {
                    SessionMeta snap = applyMeta(done.meta());
                    if (snap != null) {
                        events.add(() -> view.onMetaChanged(snap));
                    }
                    // A late/duplicate completion (cancel racing normal completion) applies meta
                    // and stays IDLE; only an active request needs dispatch or prompt cleanup.
                    if (state != State.IDLE) {
                        if (state == State.PROMPTED) {
                            activePrompt = null; // clear the prompt the terminal frame resolved
                        }
                        reply = dispatchNextOrIdle(events);
                    }
                }
                case IpcFrame.Error e -> {
                    events.add(() -> view.onError(StyledText.error("Error: " + e.content())));
                    // An IDLE client only displays the late error; active requests also advance.
                    if (state != State.IDLE) {
                        if (state == State.PROMPTED) {
                            activePrompt = null;
                        }
                        reply = dispatchNextOrIdle(events);
                    }
                }
                case IpcFrame.Terminate t -> {
                    String receivedReason = t.reason();
                    String reason =
                            receivedReason != null
                                    ? receivedReason
                                    : "Server terminated the session.";
                    activePrompt = null; // teardown — no stale prompt in a final snapshot
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
     * Logs an orphan streaming frame that arrived at {@code IDLE} (a protocol violation under 1:1)
     * and drops it — never displayed. Must hold {@link #lock}.
     */
    private void rejectOrphan(IpcFrame.@NonNull ServerFrame frame, @NonNull String kind) {
        log.warn(
                "Orphan {} frame at IDLE — no command in flight; dropping (protocol violation)",
                kind);
    }

    /**
     * Dispatches the next queued request (→ {@code RUNNING}) or goes {@code IDLE} (
     * dispatch-next-or-idle). Must hold {@link #lock}.
     *
     * @param events the event list to append a state-transition signal to
     * @return the next Request to send, or {@code null} if idle
     */
    private IpcFrame.ClientFrame dispatchNextOrIdle(@NonNull List<Runnable> events) {
        if (!pendingRequests.isEmpty()) {
            String next = pendingRequests.removeFirst();
            state = State.RUNNING;
            events.add(view::onRunning);
            events.add(() -> view.onCommandDispatched(next));
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
    private SessionMeta applyMeta(@NonNull Map<@NonNull String, Object> meta) {
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
    private void fire(@NonNull List<Runnable> events) {
        for (Runnable r : events) {
            try {
                r.run();
            } catch (Exception e) {
                log.warn("View callback threw", e);
            }
        }
    }

    public @NonNull State state() {
        synchronized (lock) {
            return state;
        }
    }

    /**
     * Atomic snapshot for prompt rendering — see {@link PromptView}. Use this instead of separate
     * {@code state} / {@code activePrompt} reads when the caller renders a prompt from the session
     * state, so the state and the active prompt describe the same moment.
     */
    public @NonNull PromptView promptView() {
        synchronized (lock) {
            return new PromptView(state, activePrompt, username);
        }
    }

    public @NonNull SessionMeta snapshot() {
        synchronized (lock) {
            return new SessionMeta(username, turnCount, sessionId);
        }
    }

    /**
     * Atomic snapshot for status-bar rendering — see {@link StatusView}. Use this instead of
     * separate {@code snapshot} / {@code pendingQueue} reads so the username and the queue describe
     * the same moment.
     */
    public @NonNull StatusView statusView() {
        synchronized (lock) {
            return new StatusView(username, turnCount, List.copyOf(pendingRequests));
        }
    }

    /** An immutable snapshot of the pending-request queue (for status-bar rendering). */
    public @NonNull List<String> pendingQueue() {
        synchronized (lock) {
            return List.copyOf(pendingRequests);
        }
    }
}
