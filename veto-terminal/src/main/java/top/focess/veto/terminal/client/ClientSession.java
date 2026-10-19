package top.focess.veto.terminal.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.contract.Delta;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.ProtocolClient;

/**
 * The terminal interaction state machine. The terminal feeds it user input ({@link #submit}, {@link
 * #cancel}) and inbound frames ({@link #onFrame}); it drives rendering back through {@link
 * ClientView}.
 *
 * <h3>States </h3>
 *
 * <ul>
 *   <li><b>{@code IDLE}</b> — no command in flight.
 *   <li><b>{@code RUNNING}</b> — one command in flight; streaming and terminal frames may arrive.
 *   <li><b>{@code PROMPTED}</b> — a command input or session approval is displayed.
 * </ul>
 *
 * <p>Deltas render in every state, including connection-local messages without a session or agent.
 * Command input requires an active command; session approvals can arrive independently and are
 * queued in arrival order. Command input takes display priority without discarding approvals. Done
 * and Error settle only the matching positive request sequence. Welcome reports the connected
 * backend version without changing command state. Terminate tears down from any state.
 *
 * <h3>Thread model</h3>
 *
 * <p>Thread-safe via a <b>self-owned internal monitor</b>. The REPL touches it from two threads
 * (the consumer thread calling {@link #onFrame}, the main thread calling {@link #submit}/{@link
 * #cancel}/snapshot between blocking {@code readLine} calls). View callbacks are collected under
 * the lock and <b>fired outside it</b> (snapshot-then-emit), so a view may re-enter the session
 * without deadlock and the critical section stays short.
 *
 * <p>The session never sends frames itself — {@link #submit}/{@link #onFrame}/{@link #cancel}
 * <b>return</b> the frame to send, or the exact cancellation target; the caller owns the {@link
 * ProtocolClient} and does the send. This keeps the core free of the transport and avoids holding
 * the monitor across a bounded-outbox {@code offer}.
 *
 * <h3>Routing on the authoritative atomic op </h3>
 *
 * <p>{@link #submit} checks the exact interaction shown before the blocking read and acts under one
 * lock. A reply to an interaction that has resolved or been replaced is discarded without echoing
 * or queuing it. Input entered at the ordinary command prompt remains a command even when an
 * interaction arrives during the read.
 */
public final class ClientSession {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.terminal.client.ClientSession");

    private static final @NonNull Set<@NonNull String> REFUSAL_OPTIONS =
            Set.of(
                    "READ_DECLINE",
                    "ABORT_WRITE",
                    "BLOCK",
                    "EXEC_DECLINE",
                    "GENERIC_DECLINE",
                    "DECLINE_AND_CONTINUE");

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
     * <p>Captured atomically by {@link #promptView} so all fields describe a single consistent
     * moment. Reading them via separate {@code state} / {@code activePrompt} calls would be a
     * time-of-check/time-of-use race — the consumer thread can transition the state machine (e.g. a
     * {@link Frame.Prompt} arriving) between the reads, so the renderer could act on a {@code
     * state} and an {@code activePrompt} that never coexisted.
     */
    public record PromptView(
            @NonNull State state,
            Frame.ServerFrame activePrompt,
            String username,
            long requestSeq) {}

    /**
     * Immutable snapshot of everything the status bar renders at one instant: session metadata and
     * the pending-request queue.
     *
     * <p>Captured atomically by {@link #statusView} so the username and the queue describe the same
     * moment. Reading them via separate {@code snapshot} / {@code pendingQueue} calls would let the
     * consumer thread mutate the session between the reads (a {@link Frame.Done} changing the
     * username, or a {@code submit} adding to the queue), so the bar could show a username and a
     * queue that never coexisted.
     */
    public record StatusView(String username, int turnCount, @NonNull List<String> pending) {}

    private final @NonNull ClientView view;
    private final @NonNull LongSupplier sequences;
    private final @NonNull Object lock = new Object();
    private final @NonNull Deque<@NonNull String> pendingRequests = new ArrayDeque<>();

    private long activeRequestSeq;
    private Frame.Prompt commandPrompt;
    private final @NonNull Deque<Frame.@NonNull Veto> approvals = new ArrayDeque<>();
    private String username;
    private int turnCount;
    private String sessionId;

    /**
     * Constructs a session that drives rendering through the given view.
     *
     * @param view the presentation seam receiving render events (fired outside the session lock)
     */
    public ClientSession(@NonNull ClientView view, @NonNull LongSupplier sequences) {
        this.view = view;
        this.sequences = sequences;
    }

    /**
     * Submits input for the exact interaction displayed before reading the line. An unchanged
     * interaction produces Input or VetoResult; a resolved or replaced interaction discards the
     * reply. Ordinary commands dispatch while idle and otherwise queue without answering a newly
     * arrived interaction.
     *
     * @param line the submitted line
     * @param displayedInteraction the captured PromptView activePrompt, or null for command input
     * @return the client frame to send, or null when queued or discarded
     */
    public Frame.ClientFrame submit(@NonNull String line, Frame.ServerFrame displayedInteraction) {
        List<Runnable> events = new ArrayList<>();
        Frame.ClientFrame frame;
        synchronized (lock) {
            if (displayedInteraction != null) {
                if (activePrompt() != displayedInteraction) return null;
                if (displayedInteraction instanceof Frame.Veto veto) {
                    frame = new Frame.VetoResult(veto.scope(), veto.callId(), line, veto.seq());
                    approvals.removeFirst();
                } else {
                    frame = new Frame.Input(line, ((Frame.Prompt) displayedInteraction).seq());
                    commandPrompt = null;
                }
                showInteraction(events, displayedInteraction);
            } else {
                pendingRequests.addLast(line);
                frame = activeRequestSeq != 0 ? null : dispatchNextOrIdle(events);
            }
        }
        fire(events);
        return frame;
    }

    /**
     * Refuses only the approval the user saw. A stale display cannot cancel a replacement approval
     * or a local command. A server approval without a known offered refusal stays visible.
     */
    public Frame.VetoResult declineApproval(Frame.@NonNull Veto displayed) {
        synchronized (lock) {
            if (activePrompt() != displayed) return null;
        }
        String refusal =
                displayed.options().stream()
                        .filter(REFUSAL_OPTIONS::contains)
                        .findFirst()
                        .orElse(null);
        if (refusal == null) {
            fire(
                    List.of(
                            () ->
                                    view.onError(
                                            StyledText.error(
                                                    "This approval has no supported refusal option; choose an offered option."))));
            return null;
        }
        var reply = submit(refusal, displayed);
        return reply instanceof Frame.VetoResult result ? result : null;
    }

    /**
     * Cancels the local command or its input prompt. Approval cancellation must instead identify
     * the exact displayed approval through declineApproval. A command still waits for Done/Error
     * before advancing its queue. Returns its exact sequence, or zero when no command is eligible.
     */
    public long cancel(long displayedRequestSeq) {
        List<Runnable> events = new ArrayList<>();
        long target;
        synchronized (lock) {
            var displayed = activePrompt();
            if (displayed instanceof Frame.Veto
                    || activeRequestSeq == 0
                    || displayedRequestSeq != activeRequestSeq) return 0;
            target = activeRequestSeq;
            commandPrompt = null;
            if (displayed != null) showInteraction(events, displayed);
        }
        fire(events);
        return target;
    }

    /**
     * Processes an inbound server frame per the state × frame matrix: updates state, applies
     * metadata, dispatches the next queued request or goes idle, and emits render events.
     *
     * <p>{@link Frame.Done} transitions the command state but never displays content — command
     * output streams via {@link Delta}; {@code Done} is the terminal marker only. {@link
     * Frame.Error} displays and transitions. Deltas also render at {@code IDLE} without changing
     * command state.
     *
     * @param frame the inbound frame
     * @return the client frame to send (a dispatched next {@link Frame.Request}), or {@code null}
     */
    public Frame.ClientFrame onFrame(Frame.@NonNull ServerFrame frame) {
        if (frame instanceof Frame.Terminate termination) {
            var reason = termination.reason();
            onConnectionClosed(reason == null ? "Server terminated the session." : reason);
            return null;
        }
        List<Runnable> events = new ArrayList<>();
        Frame.ClientFrame reply = null;
        synchronized (lock) {
            var previousInteraction = activePrompt();
            if (frame instanceof Frame.TurnOutcome outcome
                    && frame instanceof Frame.SeqResponse response
                    && activeRequestSeq != 0
                    && response.seq() == activeRequestSeq) {
                var snapshot = applyOutcome(outcome);
                if (snapshot != null) events.add(() -> view.onMetaChanged(snapshot));
            }
            switch (frame) {
                case Frame.Welcome welcome ->
                        events.add(
                                () ->
                                        view.onProgress(
                                                StyledText.muted(
                                                        "Connected to veto-core "
                                                                + welcome.productVersion()
                                                                + ".")));
                // ── streaming ───────────────────────────────────────────────────
                case Delta event -> {
                    switch (event.data()) {
                        case Delta.Progress progress ->
                                events.add(
                                        () ->
                                                view.onProgress(
                                                        StyledText.muted(
                                                                "  ⏳ " + progress.content())));
                        case Delta.Thought thought ->
                                events.add(() -> view.onThought(thought.text()));
                        case Delta.Message message ->
                                events.add(() -> view.onDelta(message.text()));
                        case Delta.Notice notice ->
                                events.add(
                                        () ->
                                                view.onProgress(
                                                        switch (notice.level()) {
                                                            case INFO ->
                                                                    StyledText.muted(notice.text());
                                                            case WARNING ->
                                                                    StyledText.warning(
                                                                            notice.text());
                                                            case ERROR ->
                                                                    StyledText.error(notice.text());
                                                        }));
                        case Delta.TokenUsage usage ->
                                events.add(
                                        () ->
                                                view.onProgress(
                                                        StyledText.muted(
                                                                "Tokens: "
                                                                        + usage.inputTokens()
                                                                        + " input, "
                                                                        + usage.outputTokens()
                                                                        + " output"
                                                                        + (usage
                                                                                                .cacheReadInputTokens()
                                                                                        == null
                                                                                ? ""
                                                                                : ", "
                                                                                        + usage
                                                                                                .cacheReadInputTokens()
                                                                                        + " cached")
                                                                        + (usage.purpose() == null
                                                                                ? ""
                                                                                : " ("
                                                                                        + usage
                                                                                                .purpose()
                                                                                        + ")"))));
                        case Delta.ToolCall call -> events.add(() -> view.onToolCall(call));
                        case Delta.ToolResult result -> {
                            var previous = activePrompt();
                            approvals.removeIf(
                                    veto ->
                                            veto.scope().equals(event.scope())
                                                    && veto.callId().equals(result.callId()));
                            events.add(() -> view.onToolResult(result));
                            if (previous != activePrompt()) showInteraction(events, previous);
                        }
                        default -> {}
                    }
                }
                // Resource snapshots are fetched by commands; a hint never completes one.
                case Frame.Action ignored -> {}
                // Admission acknowledgement never settles a command or a replacement prompt.
                case Frame.CancelResult ignored -> {}
                case Frame.RequestAccepted ignored -> {}
                case Frame.PluginAction event ->
                        events.add(
                                () ->
                                        view.onDelta(
                                                event.topic()
                                                        + ": "
                                                        + event.data().toPrettyString()));
                case Frame.Prompt prompt -> {
                    if (activeRequestSeq == 0 || prompt.seq() != activeRequestSeq) {
                        log.warn("Command prompt arrived without a command in flight; dropping");
                        break;
                    }
                    var previous = activePrompt();
                    commandPrompt = prompt;
                    showInteraction(events, previous);
                }
                case Frame.Veto veto -> {
                    if (approvals.stream().anyMatch(pending -> sameApproval(pending, veto))) break;
                    var previous = activePrompt();
                    approvals.addLast(veto);
                    if (previous == null) showInteraction(events, null);
                }

                // ── terminal ────────────────────────────────────────────────────
                case Frame.Done done -> {
                    // Only command completion advances the input queue; display state is separate.
                    if (activeRequestSeq != 0 && done.seq() == activeRequestSeq) {
                        commandPrompt = null;
                        reply = dispatchNextOrIdle(events);
                    }
                }
                case Frame.Error e -> {
                    events.add(() -> view.onError(StyledText.error("Error: " + e.content())));
                    // Query failures, including late replies, never complete a command.
                    if (e.seq() == 0 || e.seq() != activeRequestSeq) break;
                    // An IDLE client only displays the late error; active requests also advance.
                    if (activeRequestSeq != 0) {
                        commandPrompt = null;
                        reply = dispatchNextOrIdle(events);
                    }
                }
                default ->
                        log.warn(
                                "Unexpected server frame {} — ignoring",
                                frame.getClass().getSimpleName());
            }
            if (frame instanceof Frame.TurnOutcome
                    && previousInteraction != null
                    && activePrompt() == null) events.add(view::onPromptCleared);
        }
        fire(events);
        return reply;
    }

    /** Releases local interaction state after EOF, local failure or server termination. */
    public void onConnectionClosed(@NonNull String reason) {
        synchronized (lock) {
            commandPrompt = null;
            approvals.clear();
            pendingRequests.clear();
            activeRequestSeq = 0;
        }
        view.onTerminate(StyledText.muted(reason));
    }

    private static boolean sameApproval(Frame.@NonNull Veto first, Frame.@NonNull Veto second) {
        return first.scope().equals(second.scope())
                && first.callId().equals(second.callId())
                && first.seq() == second.seq();
    }

    /** All interaction state is derived under lock; approval queues never own command progress. */
    private Frame.ServerFrame activePrompt() {
        return commandPrompt != null ? commandPrompt : approvals.peekFirst();
    }

    private @NonNull State currentState() {
        return activePrompt() != null
                ? State.PROMPTED
                : activeRequestSeq != 0 ? State.RUNNING : State.IDLE;
    }

    private void showInteraction(@NonNull List<Runnable> events, Frame.ServerFrame previous) {
        var displayed = activePrompt();
        if (displayed != null) {
            if (previous == null) events.add(view::onPrompted);
            events.add(() -> view.onPrompt(displayed));
        } else {
            if (previous != null) events.add(view::onPromptCleared);
            events.add(activeRequestSeq != 0 ? view::onRunning : view::onIdle);
        }
    }

    /** Only a command completion or idle command submission advances this queue. */
    private Frame.ClientFrame dispatchNextOrIdle(@NonNull List<Runnable> events) {
        if (!pendingRequests.isEmpty()) {
            String next = pendingRequests.removeFirst();
            activeRequestSeq = sequences.getAsLong();
            showInteraction(events, null);
            events.add(() -> view.onCommandDispatched(next));
            return new Frame.Request(activeRequestSeq, null, null, next);
        }
        activeRequestSeq = 0;
        showInteraction(events, null);
        return null;
    }

    /** Each command outcome carries the current login and session; -1 preserves the turn count. */
    private SessionMeta applyOutcome(Frame.@NonNull TurnOutcome outcome) {
        var scope = outcome.scope();
        var session = scope.session();
        int turns =
                outcome.turnNumber() >= 0
                        ? outcome.turnNumber()
                        : Objects.equals(sessionId, session) && outcome.username() != null
                                ? turnCount
                                : 0;
        if (Objects.equals(username, outcome.username())
                && Objects.equals(sessionId, session)
                && turnCount == turns) return null;
        if (!Objects.equals(username, outcome.username()) || !Objects.equals(sessionId, session))
            approvals.removeIf(
                    approval ->
                            !Objects.equals(approval.scope().userId(), scope.userId())
                                    || !Objects.equals(approval.scope().session(), session));
        username = outcome.username();
        sessionId = session;
        turnCount = turns;
        return new SessionMeta(username, turnCount, sessionId);
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
            return currentState();
        }
    }

    /**
     * Atomic snapshot for prompt rendering — see {@link PromptView}. Use this instead of separate
     * {@code state} / {@code activePrompt} reads when the caller renders a prompt from the session
     * state, so the state and the active prompt describe the same moment.
     */
    public @NonNull PromptView promptView() {
        synchronized (lock) {
            return new PromptView(currentState(), activePrompt(), username, activeRequestSeq);
        }
    }

    /** Atomic snapshot of session metadata (username, turn count, session id) for rendering. */
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
