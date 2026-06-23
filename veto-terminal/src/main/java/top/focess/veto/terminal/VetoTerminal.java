package top.focess.veto.terminal;

import com.github.ajalt.mordant.terminal.Terminal;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.NotNull;
import org.jline.reader.*;
import org.jline.terminal.TerminalBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.client.core.ClientSession;
import top.focess.veto.client.core.ClientView;
import top.focess.veto.client.core.Logging;
import top.focess.veto.client.core.StyleToken;
import top.focess.veto.client.core.StyledText;
import top.focess.veto.contract.ClientOptions;
import top.focess.veto.contract.IpcClient;
import top.focess.veto.contract.IpcFrame;

/**
 * Main interactive REPL terminal controller for Veto Core.
 *
 * <p>Reads user input via JLine {@link LineReader} and coordinates with the backend via {@link
 * IpcClient}. The interaction protocol (session state, request pipeline, frame dispatch) lives in a
 * shared {@link ClientSession}; this class owns only the REPL presentation — the prompt, the
 * inline-above-prompt rendering, and the one retained interrupt for the mid-line prompt swap.
 *
 * <h3>Threading</h3>
 *
 * <ul>
 *   <li><b>Main thread</b> — blocks in {@link LineReader#readLine}; on each return it asks the
 *       session for the current state to render the prompt, then submits the line.
 *   <li><b>Consumer thread ({@code veto-incoming})</b> — drains {@link IpcClient#receive()} into
 *       {@link ClientSession#onFrame}, which drives rendering back through {@link TerminalView} and
 *       returns the next frame to dispatch (sent here).
 * </ul>
 *
 * <h3>The one retained interrupt</h3>
 *
 * <p>When a {@link IpcFrame.Prompt} arrives, the consumer thread (via {@link
 * TerminalView#onPrompt}) sets {@link #promptSwapPending} and <b>then</b> interrupts the main
 * thread to break its blocking {@code readLine}. The main thread's {@code UserInterruptException}
 * catch distinguishes the two wake causes by reading the flag <b>after</b> {@code readLine} throws:
 * flag set ⇒ a Prompt-swap re-render; flag unset ⇒ a genuine Ctrl+C (the {@code 0x03} byte, never a
 * {@code Thread.interrupt()}) ⇒ cancel. Reading the flag after the throw — not clearing a "stale"
 * interrupt at the loop top — is what keeps the distinction sound: the consumer's flag-set and
 * interrupt are not atomic, so a loop-top clear could drain a not-yet-delivered interrupt and then
 * misread the swap's late interrupt as Ctrl+C (exiting the terminal on a stray Prompt), or fall
 * through to {@code readLine} with a stale prompt and a null mask (a password echoed in plaintext).
 * See the catch in {@link #repl()} for the full rationale.
 */
public class VetoTerminal {

    private static final Logger log = LoggerFactory.getLogger(VetoTerminal.class);

    /** ASCII Banner displayed on startup. */
    private static final String HEADER =
            """
                    ██╗   ██╗███████╗████████╗ ██████╗
                    ██║   ██║██╔════╝╚══██╔══╝██╔═══██╗
                    ██║   ██║█████╗     ██║   ██║   ██║
                    ╚██╗ ██╔╝██╔══╝     ██║   ██║   ██║
                     ╚████╔╝ ███████╗   ██║   ╚██████╔╝
                      ╚═══╝  ╚══════╝   ╚═╝    ╚═════╝
                    """;

    /** The Mordant terminal used for styled (ANSI) console output. */
    private final Terminal t;

    /** IPC connection to the backend (transport-agnostic; ZMQ locally). */
    private final IpcClient client;

    /** JLine reader driving the interactive REPL; set in {@link #start(LineReader)}. */
    private LineReader reader;

    /** Output seam — prints above the active prompt via {@link LineReader#printAbove}. */
    private MordantRenderer renderer;

    /** Maps {@link StyleToken}s to Mordant ANSI strings. */
    private MordantTheme theme;

    /** Bottom status bar view over the session. */
    private TerminalStatus status;

    /** The shared interaction protocol (session state, request pipeline, frame dispatch). */
    private ClientSession session;

    /** Reference to the main REPL thread so the consumer can interrupt its blocking readLine. */
    private Thread mainThread;

    /** Set to {@code false} to signal all background threads to stop. */
    private volatile boolean running;

    /**
     * The sole swap-wake signal: set by the consumer thread (via {@link TerminalView#onPrompt}) to
     * request that the main thread's next {@code readLine} wake be treated as a Prompt-swap
     * re-render rather than a genuine Ctrl+C. The consumer sets this <b>before</b> calling {@link
     * #mainThread}{@code .interrupt()} (which breaks the blocking {@code readLine}), so "an
     * interrupt fired" implies "the flag was set."
     *
     * <p>It is read and cleared in exactly one place: the {@code UserInterruptException} catch in
     * {@link #repl()}, <b>after</b> {@code readLine} throws. Reading/clearing it after the throw
     * (not at the loop top) is what makes the swap/Ctrl+C distinction sound — see the catch for the
     * full rationale (a loop-top clear races the consumer's non-atomic flag-set + interrupt,
     * causing a phantom-Ctrl+C terminal exit and a stale-prompt/mask plaintext leak).
     */
    private volatile boolean promptSwapPending;

    /**
     * Constructs a new VetoTerminal instance.
     *
     * @param t the Mordant Terminal instance used for styled outputs
     * @param client the IpcClient used for IPC communications with the backend
     */
    public VetoTerminal(@NotNull Terminal t, @NotNull IpcClient client) {
        this.t = t;
        this.client = client;
    }

    /**
     * Initializes the terminal components, starts the consumer thread, and enters the interactive
     * REPL loop. Ensures clean resource teardown on shutdown.
     *
     * @param reader the JLine LineReader instance to read inputs from
     */
    public void start(@NotNull LineReader reader) {
        this.reader = reader;
        this.mainThread = Thread.currentThread();
        this.theme = new MordantTheme(t);
        this.renderer = new MordantRenderer(reader);
        this.session = new ClientSession(new TerminalView());
        this.status = new TerminalStatus(reader.getTerminal(), session, theme);
        status.refresh();

        printBanner();
        running = true;

        // Heartbeats are sent by the IpcClient itself (its ipc-hb thread).

        // --- hint widgets ---
        // Binds custom parameter autocomplete / tail-tip widgets to JLine reader.
        VetoHintWidgets hintWidgets = new VetoHintWidgets(reader, client);
        hintWidgets.enable();

        // --- incoming consumer thread ---
        // Drains frames from the connection through the session, which drives rendering back via
        // TerminalView and returns the next frame to dispatch (if any).
        Thread consumerThread =
                new Thread(
                        () -> {
                            while (running) {
                                try {
                                    IpcFrame.ServerFrame frame = client.receive();
                                    if (frame == null) {
                                        continue;
                                    }
                                    IpcFrame.ClientFrame reply = session.onFrame(frame);
                                    if (reply != null) {
                                        client.send(reply);
                                    }
                                } catch (Exception e) {
                                    if (running) {
                                        log.warn("Error in incoming loop, terminating thread", e);
                                    }
                                    break;
                                }
                            }
                        },
                        "veto-incoming");
        consumerThread.setDaemon(true);
        consumerThread.start();

        try {
            // Enter the main interactive loop.
            repl();
        } finally {
            // Teardown: stop loops, disable widgets, interrupt the consumer, clear the status bar,
            // and close the connection (close() flushes the Bye frame before teardown).
            running = false;
            hintWidgets.disable();
            consumerThread.interrupt();
            status.clear();
            client.close();
        }
    }

    // ── repl ──────────────────────────────────────────────────────────────

    /**
     * The main interactive REPL read-eval-print loop. Renders the prompt from the current session
     * state, reads a line, and submits it (a command, a prompt reply, or a cancel).
     */
    private void repl() {
        while (running) {
            // One atomic snapshot — state, activePrompt and username describe a single moment.
            // Reading them via separate calls would be a TOCTOU: the consumer thread can transition
            // the state machine (a Prompt arriving) between the reads, so the rendered prompt could
            // disagree with the state it was chosen for.
            ClientSession.PromptView view = session.promptView();
            ClientSession.State state = view.state();
            IpcFrame.Prompt activePrompt = view.activePrompt();

            String promptText;
            Character mask;
            if (state == ClientSession.State.PROMPTED && activePrompt != null) {
                // Server-prompted input request.
                promptText =
                        theme.style(StyleToken.PROMPT, "▸")
                                + " "
                                + theme.style(StyleToken.ACCENT, activePrompt.content())
                                + " ";
                mask = activePrompt.mask() ? '*' : null;
                reader.setVariable(LineReader.DISABLE_HISTORY, Boolean.TRUE);
            } else {
                // Standard prompt: green when logged in, red when logged out.
                boolean loggedIn = view.username() != null;
                promptText =
                        loggedIn
                                ? theme.style(StyleToken.SUCCESS, "▸ ")
                                : theme.style(StyleToken.ERROR, "◇ ");
                mask = null;
                reader.setVariable(LineReader.DISABLE_HISTORY, Boolean.FALSE);
            }

            String line;
            try {
                line = reader.readLine(promptText, mask);
            } catch (UserInterruptException e) {
                // ── Why readLine threw, and how we tell the two causes apart ──────────────
                //
                // readLine can throw UserInterruptException for two distinct reasons:
                //
                //   (1) A Prompt swap — the consumer thread (on a backend Prompt frame) set
                //       promptSwapPending = true and then called mainThread.interrupt() to break
                //       this blocking readLine so the loop re-renders with the PROMPTED prompt.
                //   (2) A genuine Ctrl+C — the user pressed Ctrl+C (the tty sent 0x03), which JLine
                //       reads during readLine and turns into this exception.
                //
                // We distinguish them by the flag, and the distinction is sound ONLY because the
                // consumer sets the flag BEFORE interrupting (onPrompt: flag = true; then
                // interrupt)
                // and because the flag is cleared in exactly one place — here, in the swap branch.
                // So "the interrupt fired" ⇒ "the flag was set by the swap that caused it," and a
                // read of the flag here is authoritative. (A real Ctrl+C never sets
                // promptSwapPending;
                // it arrives as the 0x03 byte, not as Thread.interrupt(), so it leaves the flag
                // false.)
                //
                // ── Why the flag clear + interrupt drain lives HERE, not at the loop top ──────
                //
                // An earlier version drained a "stale swap interrupt" at the top of the loop,
                // before
                // readLine. That was unsound: the consumer's "set flag" and "interrupt" are NOT
                // atomic, so the loop-top drain could observe the flag, clear it, and drain the
                // interrupt flag BEFORE the consumer's interrupt() had even been delivered. Two
                // races
                // followed:
                //
                //   ABA / phantom Ctrl+C: flag set (1) → loop-top sees flag, clears it, drains a
                //     NOT-YET-DELIVERED interrupt (no-op) → readLine blocks → consumer's
                // interrupt()
                //     finally lands on the blocked readLine → readLine throws → catch sees flag now
                //     FALSE (cleared at the top) → misreads the swap as a genuine Ctrl+C → at IDLE,
                //     cancel() returns null (shutdown) → the terminal EXITS on a stray backend
                // Prompt.
                //
                //   Stale prompt/mask: a Prompt landing between the snapshot and readLine left the
                //     loop rendering the old prompt with mask=null (a password echoed in
                // plaintext).
                //
                // Moving the drain here fixes both: the swap interrupt is drained only AFTER
                // readLine
                // has actually thrown from it (so the interrupt we clear provably corresponds to
                // this
                // swap), and the `continue` re-snapshots promptView() — so a Prompt that arrived
                // after
                // the prior snapshot is picked up with its correct prompt + mask. The flag is the
                // only
                // swap signal and it is read exactly once, after the wake, with no intervening
                // clear.
                if (promptSwapPending) {
                    promptSwapPending = false;
                    // Drain the interrupt flag. This swap woke readLine via mainThread.interrupt();
                    // JLine3 (LineReaderImpl.readLine) turns that into this UserInterruptException
                    // and, in its own finally, RE-SETS the flag (Thread.interrupted() to
                    // capture+clear,
                    // then Thread.currentThread().interrupt() if it was set) — so on arrival here
                    // the
                    // flag is set. We must clear it (Thread.interrupted()) before the next
                    // readLine, or
                    // JLine's non-blocking reader sees the set flag and throws again on the very
                    // next
                    // read, looping the re-render forever. Clearing it here is sound: the throw
                    // proves
                    // the swap's interrupt was actually delivered, so the flag we drain provably
                    // belongs
                    // to this swap (not some stale interrupt — the old loop-top drain raced a
                    // not-yet-delivered interrupt).
                    Thread.interrupted();
                    continue; // re-snapshot at the loop top with the now-live PROMPTED state
                }
                // Flag false ⇒ not a swap ⇒ genuine Ctrl+C → cancel the in-flight request/prompt,
                // or exit if idle (cancel() returns null as the shutdown signal per §6.2 IDLE ×
                // cancel).
                IpcFrame.Cancel cancel = session.cancel();
                if (cancel == null) {
                    break; // idle → exit the REPL
                }
                client.send(cancel);
                continue;
            } catch (EndOfFileException e) {
                break;
            }

            if (line == null) break;
            line = line.trim();
            if (line.isEmpty()) continue;

            // Route the line authoritatively: submit() checks the *current* state under its lock
            // and returns exactly the frame to send — Input for a prompt reply, Request for a
            // dispatched new command, or null if enqueued while awaiting (or discarded as a stale
            // reply). Routing on the `state` captured above would be a TOCTOU: the consumer thread
            // can transition the state machine during the blocking readLine (a Prompt arriving, or
            // a
            // Done/Error completing the in-flight request), so the render-time snapshot may no
            // longer hold when the user submits. The snapshot is used only to *render* the prompt,
            // never to route the reply. A dispatched command is echoed by onCommandDispatched
            // (fired
            // by submit on dispatch, and by onFrame when a queued command is auto-dispatched) — so
            // a
            // queued command echoes when it actually runs, not when it is merely typed — and the
            // call site need not distinguish Input from Request: both just get sent, and only the
            // null case (enqueued / discarded) sends nothing.
            IpcFrame.ClientFrame reply = session.submit(line);
            if (reply != null) {
                client.send(reply);
            }
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────

    /**
     * Echoes the user's input wrapped in a clean, framed box for visual clarity.
     *
     * @param line the input text
     */
    private void echoInput(@NotNull String line) {
        int termWidth = reader.getTerminal().getWidth();
        int maxWidth = termWidth > 0 ? termWidth - 4 : 76;
        int borderLen = Math.max(0, Math.min(maxWidth, line.length() + 4));
        renderer.println("");
        renderer.println(theme.style(StyleToken.BORDER, "  ╭─ you " + "─".repeat(borderLen)));
        renderer.println("  │ " + line);
        renderer.println(
                theme.style(
                        StyleToken.BORDER, "  ╰" + "─".repeat(Math.min(maxWidth, borderLen + 4))));
    }

    /** Prints the startup ASCII banner header. */
    private void printBanner() {
        for (String line : HEADER.split("\n")) {
            renderer.println(theme.styleBold(StyleToken.ACCENT, line));
        }
        renderer.println(theme.style(StyleToken.MUTED, "  terminal v3.0"));
        renderer.println("");
    }

    // ── ClientView ────────────────────────────────────────────────────────

    /**
     * {@link ClientView} adapter rendering session events to the REPL. Runs on the consumer thread;
     * all output goes through {@link LineReader#printAbove} (thread-safe) and {@link
     * TerminalStatus#refresh} (reads session snapshots under the session's own lock).
     */
    private final class TerminalView implements ClientView {

        @Override
        public void onDelta(@NotNull String content) {
            renderer.println(content);
        }

        @Override
        public void onProgress(@NotNull StyledText content) {
            renderer.println(theme.style(content.token(), content.text()));
        }

        @Override
        public void onPrompt(@NotNull IpcFrame.Prompt prompt) {
            // The session already set state=PROMPTED. Signal the main thread to re-render with the
            // prompted prompt: set the flag FIRST, then interrupt readLine. The ordering matters —
            // "interrupt fired" must imply "flag was set" so the catch can distinguish this
            // swap-wake
            // from a genuine Ctrl+C (see the UserInterruptException catch in repl()).
            promptSwapPending = true;
            mainThread.interrupt();
        }

        @Override
        public void onError(@NotNull StyledText content) {
            renderer.println(theme.style(content.token(), content.text()));
        }

        @Override
        public void onTerminate(@NotNull StyledText content) {
            renderer.println(theme.style(content.token(), content.text()));
            running = false;
            // The main thread is blocked in readLine; setting running=false alone won't wake it, so
            // the prompt would keep blinking (the session is dead but the REPL looks alive) until
            // the user presses Enter. Interrupt to break readLine — the UserInterruptException
            // catch
            // then sees promptSwapPending is false (a Terminate is not a swap) and routes to
            // cancel(), which always ends the iteration (null at IDLE → break; or a Cancel at
            // RUNNING
            // → send + continue → while(running) is now false → exit). Either way the REPL exits
            // and
            // teardown runs, promptly telling the user the session is over.
            mainThread.interrupt();
        }

        @Override
        public void onIdle() {
            status.refresh();
        }

        @Override
        public void onRunning() {
            status.refresh();
        }

        @Override
        public void onCommandDispatched(@NotNull String line) {
            // Fires on whichever thread performed the dispatch: the main thread (a line typed at
            // IDLE) or the consumer thread (a queued command auto-dispatched from onFrame).
            // printAbove is thread-safe, so both paths render uniformly.
            if (!line.startsWith("/")) {
                echoInput(line);
                renderer.println(theme.style(StyleToken.MUTED, "  thinking…"));
            }
        }

        @Override
        public void onMetaChanged(@NotNull ClientSession.SessionMeta meta) {
            status.refresh();
        }
    }

    // ── tab completion ───────────────────────────────────────────────────

    /**
     * JLine {@link Completer} that fetches tab-completion candidates from the backend.
     *
     * <p>Completion is triggered only when the buffer starts with {@code /} (slash-commands).
     * Candidates are retrieved synchronously via {@link IpcClient#complete} with a 3-second
     * timeout; if the backend does not respond in time, no candidates are offered.
     */
    private class VetoCompleter implements Completer {

        @Override
        public void complete(
                @NotNull LineReader r, @NotNull ParsedLine line, @NotNull List<Candidate> out) {
            String fullLine = line.line();
            // Completion is only requested for slash commands.
            if (!fullLine.startsWith("/")) return;
            IpcFrame.CompleteResult compResult = client.complete(fullLine, 2, TimeUnit.SECONDS);
            if (compResult != null) {
                for (IpcFrame.Completion comp : compResult.candidates()) {
                    String name = comp.value();
                    String desc =
                            comp.description() != null && !comp.description().isEmpty()
                                    ? comp.description()
                                    : null;
                    String group =
                            comp.group() != null && !comp.group().isEmpty() ? comp.group() : null;
                    out.add(new Candidate(name, name, group, desc, null, null, true));
                }
            }
        }
    }

    // ── main ──────────────────────────────────────────────────────────────

    /**
     * Main entry point for starting the VetoTerminal client.
     *
     * @param args command line arguments, supports --debug or -d to enable log file output
     */
    public static void main(@NotNull String[] args) {
        System.setProperty("file.encoding", "UTF-8");

        ClientOptions options = ClientOptions.parse(args);
        Logging.configure(options.debug());

        try {
            // Build the system JLine Terminal and the Mordant Terminal.
            org.jline.terminal.Terminal jt =
                    TerminalBuilder.builder().system(true).jna(true).encoding("UTF-8").build();
            Terminal mt = MordantTerminal.create();
            System.out.println("Connecting to backend at " + options.address() + " ...");
            IpcClient transport = new IpcClient(options.address());
            System.out.println("Connected.");
            VetoTerminal vt = new VetoTerminal(mt, transport);
            Completer completer = vt.new VetoCompleter();
            LineReader r = LineReaderBuilder.builder().terminal(jt).completer(completer).build();
            r.setVariable(LineReader.HISTORY_FILE, Path.of(".veto_history"));
            vt.start(r);
        } catch (IOException e) {
            System.err.println("Terminal failed: " + e.getMessage());
        }
    }
}
