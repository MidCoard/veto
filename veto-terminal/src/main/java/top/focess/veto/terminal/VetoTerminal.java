package top.focess.veto.terminal;

import com.github.ajalt.mordant.terminal.Terminal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.jline.reader.Completer;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.TerminalBuilder;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.api.util.Threads;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.ProtocolClient;
import top.focess.veto.contract.Version;
import top.focess.veto.terminal.client.ClientOptions;
import top.focess.veto.terminal.client.ClientSession;
import top.focess.veto.terminal.client.Logging;
import top.focess.veto.terminal.client.StyleToken;
import top.focess.veto.transport.tcp.TcpConnection;

/**
 * Main interactive REPL terminal controller for Veto Core.
 *
 * <p>Reads user input via JLine {@link LineReader} and coordinates with the backend via {@link
 * ProtocolClient}. The interaction protocol (session state, request pipeline, frame dispatch) lives
 * in a shared {@link ClientSession}; this class owns only the REPL presentation — the prompt, the
 * inline-above-prompt rendering, and the one retained interrupt for the mid-line prompt swap.
 *
 * <h3>Threading</h3>
 *
 * <ul>
 *   <li><b>Main thread</b> — blocks in {@link LineReader#readLine}; on each return it asks the
 *       session for the current state to render the prompt, then submits the line.
 *   <li><b>Virtual consumer ({@code veto-incoming})</b> — drains {@link ProtocolClient#receive}
 *       into {@link ClientSession#onFrame}, which drives rendering back through {@link
 *       TerminalView} and returns the next frame to dispatch (sent here).
 * </ul>
 *
 * <p>The interaction monitor keeps each state transition and its outbound queue admission in order
 * across both threads. Reading input, receiving frames, and shutdown joins run outside it. View
 * callbacks may render or interrupt the reader but never wait for that thread to finish.
 *
 * <h3>The one retained interrupt</h3>
 *
 * <p>When a {@link Frame.Prompt} arrives, the consumer thread (via {@link TerminalView#onPrompt})
 * sets {@link #promptSwapPending} and <b>then</b> interrupts the main thread to break its blocking
 * {@code readLine}. The main thread's {@code UserInterruptException} catch distinguishes the two
 * wake causes by reading the flag <b>after</b> {@code readLine} throws: flag set ⇒ a Prompt-swap
 * re-render; flag unset ⇒ a genuine Ctrl+C (the {@code 0x03} byte, never a {@code
 * Thread.interrupt}) ⇒ cancel. Reading the flag after the throw — not clearing a "stale" interrupt
 * at the loop top — is what keeps the distinction sound: the consumer's flag-set and interrupt are
 * not atomic, so a loop-top clear could drain a not-yet-delivered interrupt and then misread the
 * swap's late interrupt as Ctrl+C (exiting the terminal on a stray Prompt), or fall through to
 * {@code readLine} with a stale prompt and a null mask (a password echoed in plaintext). See the
 * catch in {@link #repl} for the full rationale.
 */
@SuppressWarnings(
        "NotNullFieldNotInitialized") // start(LineReader) initializes the REPL lifecycle fields.
public class VetoTerminal {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.terminal.VetoTerminal");

    /** ASCII Banner displayed on startup. */
    private static final @NonNull String HEADER =
            """
                    ██╗   ██╗███████╗████████╗ ██████╗
                    ██║   ██║██╔════╝╚══██╔══╝██╔═══██╗
                    ██║   ██║█████╗     ██║   ██║   ██║
                    ╚██╗ ██╔╝██╔══╝     ██║   ██║   ██║
                     ╚████╔╝ ███████╗   ██║   ╚██████╔╝
                      ╚═══╝  ╚══════╝   ╚═╝    ╚═════╝
                    """;

    /** The Mordant terminal used for styled (ANSI) console output. */
    private final @NonNull Terminal t;

    /** Protocol connection to the backend over TCP. */
    private final @NonNull ProtocolClient client;

    /** Orders interaction transitions with their nonblocking protocol queue admission. */
    private final @NonNull Object interactionLock = new Object();

    /** JLine reader driving the interactive REPL; set in {@link #start(LineReader)}. */
    private @NonNull LineReader reader;

    /** Output seam — prints above the active prompt via {@link LineReader#printAbove}. */
    private @NonNull MordantRenderer renderer;

    /** Maps {@link StyleToken}s to Mordant ANSI strings. */
    private @NonNull MordantTheme theme;

    /** Bottom status bar view over the session. */
    private @NonNull TerminalStatus status;

    /** The shared interaction protocol (session state, request pipeline, frame dispatch). */
    private @NonNull ClientSession session;

    /** Reference to the main REPL thread so the consumer can interrupt its blocking readLine. */
    private @NonNull Thread mainThread;

    /** Set to {@code false} to signal all background threads to stop. */
    private volatile boolean running;

    /**
     * The sole swap-wake signal: set by the consumer thread (via {@link TerminalView#onPrompt}) to
     * request that the main thread's next {@code readLine} wake be treated as a Prompt-swap
     * re-render rather than a genuine Ctrl+C. The consumer sets this <b>before</b> calling {@link
     * Thread#interrupt} (which breaks the blocking {@code readLine}), so "an interrupt fired"
     * implies "the flag was set."
     *
     * <p>It is read and cleared in exactly one place: the {@code UserInterruptException} catch in
     * {@link #repl}, <b>after</b> {@code readLine} throws. Reading/clearing it after the throw (not
     * at the loop top) is what makes the swap/Ctrl+C distinction sound — see the catch for the full
     * rationale (a loop-top clear races the consumer's non-atomic flag-set + interrupt, causing a
     * phantom-Ctrl+C terminal exit and a stale-prompt/mask plaintext leak).
     */
    private volatile boolean promptSwapPending;

    /**
     * Constructs a new VetoTerminal instance.
     *
     * @param t the Mordant Terminal instance used for styled outputs
     * @param client the ProtocolClient used for IPC communications with the backend
     */
    // start(LineReader) is the lifecycle initializer: all remaining fields are assigned before any
    // private REPL or ClientView path is reachable. Checker cannot model this two-phase API.
    @SuppressWarnings({"initialization.fields.uninitialized", "NotNullFieldNotInitialized"})
    public VetoTerminal(@NonNull Terminal t, @NonNull ProtocolClient client) {
        this.t = t;
        this.client = client;
    }

    /**
     * Initializes the terminal components, starts the consumer thread, and enters the interactive
     * REPL loop. Ensures clean resource teardown on shutdown.
     *
     * @param reader the JLine LineReader instance to read inputs from
     */
    public void start(@NonNull LineReader reader) {
        this.reader = reader;
        this.mainThread = Thread.currentThread();
        this.theme = new MordantTheme(t);
        this.renderer = new MordantRenderer(reader);
        // Construction stores the callbacks; status is ready before either REPL thread uses them.
        this.session =
                new ClientSession(
                        new TerminalView(
                                reader,
                                renderer,
                                theme,
                                this::refreshPrompt,
                                this::terminate,
                                () -> status.refresh()),
                        client::nextSequence);
        this.status = new TerminalStatus(reader.getTerminal(), session, theme);
        status.refresh();

        printBanner();
        running = true;

        // The protocol IO owner also schedules heartbeats.

        // --- hint widgets ---
        // Binds custom parameter autocomplete / tail-tip widgets to JLine reader.
        VetoHintWidgets hintWidgets = new VetoHintWidgets(reader, client);
        hintWidgets.enable();

        // --- incoming consumer thread ---
        // Drains frames from the connection through the session, which drives rendering back via
        // TerminalView and returns the next frame to dispatch (if any).
        Thread consumerThread = Thread.ofVirtual().name("veto-incoming").start(this::consumeFrames);

        try {
            // Enter the main interactive loop.
            repl();
        } finally {
            // Stop rendering before restoring the terminal and closing its status bar.
            running = false;
            client.close();
            Threads.awaitTermination(consumerThread, 2_000);
            hintWidgets.disable();
            status.close();
        }
    }

    private void consumeFrames() {
        try {
            while (running) {
                var frame = client.receive();
                if (!running) return;
                if (frame == null) {
                    acceptFrame(new Frame.Terminate("Backend connection closed."));
                    return;
                }
                acceptFrame(frame);
            }
        } catch (Throwable failure) {
            if (running) {
                log.warn("Terminal receive failed ({})", failure.getClass().getSimpleName());
                acceptFrame(new Frame.Terminate("Backend connection failed."));
            }
        }
    }

    /** An accepted reply enters the outbox before completion can dispatch another command. */
    void submitInput(@NonNull String line, Frame.ServerFrame displayedInteraction) {
        synchronized (interactionLock) {
            var reply = session.submit(line, displayedInteraction);
            if (reply != null) client.send(reply);
        }
    }

    /** Inbound transitions and any resulting command share the same ordering boundary. */
    void acceptFrame(Frame.@NonNull ServerFrame frame) {
        synchronized (interactionLock) {
            var reply = session.onFrame(frame);
            if (reply != null) client.send(reply);
        }
    }

    /** Returns false when idle cancellation means the REPL should exit. */
    private boolean cancelCommand(ClientSession.@NonNull PromptView displayed) {
        synchronized (interactionLock) {
            if (displayed.activePrompt() instanceof Frame.Veto veto) {
                var refusal = session.declineApproval(veto);
                if (refusal != null) client.send(refusal);
                return true;
            }
            var current = session.promptView();
            if (current.activePrompt() instanceof Frame.Veto) return true;
            long targetSeq = session.cancel(displayed.requestSeq());
            if (targetSeq == 0) return displayed.requestSeq() != 0 || current.requestSeq() != 0;
            client.cancel(targetSeq);
            return true;
        }
    }

    // ── repl ──────────────────────────────────────────────────────────────

    /**
     * The main interactive REPL read-eval-print loop. Renders the prompt from the current session
     * state, reads a line, and submits it (a command, a prompt reply, or a cancel).
     */
    @SuppressWarnings("argument") // JLine uses null mask to request unmasked input.
    private void repl() {
        while (running) {
            ClientSession.PromptView view = session.promptView();
            ClientSession.State state = view.state();
            Frame.ServerFrame interaction = view.activePrompt();
            Frame.Prompt activePrompt = interaction instanceof Frame.Prompt text ? text : null;
            Frame.Veto activeVeto = interaction instanceof Frame.Veto veto ? veto : null;

            String promptText;
            Character mask;
            if (state == ClientSession.State.PROMPTED && interaction != null) {
                if (activeVeto != null) {
                    // HITL veto: render a numbered option picker above the prompt. The reply is
                    // resolved to an option name (by index or name) after readLine and sent as an
                    // VetoResult frame matching the parked approval.
                    renderVetoPicker(activeVeto);
                    promptText =
                            theme.style(StyleToken.PROMPT, "▸")
                                    + " "
                                    + theme.style(StyleToken.ACCENT, "veto " + activeVeto.tool())
                                    + " pick> ";
                    mask = null;
                    reader.setVariable(LineReader.DISABLE_HISTORY, Boolean.TRUE);
                } else if (activePrompt != null) {
                    // Server-prompted input request.
                    promptText =
                            theme.style(StyleToken.PROMPT, "▸")
                                    + " "
                                    + theme.style(StyleToken.ACCENT, activePrompt.content())
                                    + " ";
                    mask = activePrompt.mask() ? '*' : null;
                    reader.setVariable(LineReader.DISABLE_HISTORY, Boolean.TRUE);
                } else {
                    throw new IllegalStateException("Unknown interaction");
                }
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
                if (!running) break;
                // two ways to trigger this, first the user press the ctrl+C,
                // second the consumerThread actively call the mainThread to interrupt.
                // when consumerThread actively call the mainThread to interrupt, the
                // promptSwapPending flag is always first set
                // and then the mainThread interrupts, so when two ways come both, there are
                // multiple cases.
                // case 1: the ctrl+C is always first, so we move to cancel, it is expected
                // behavior.
                // case 2: the ctrl+C is first, but promptSwapPending is true when codes come to
                // here, and until calling the "continue", the mainThread is not interrupted.
                // so in this case, when come to reader.readLine again, another
                // UserInterruptException is thrown, and we move to cancel, this is also expected.
                // case 3: the ctrl+C is first, but promptSwapPending is true and the mainThread
                // interrupts when codes come to here, so thread's interrupt status is cleared.
                // when codes come to reader.readLine again, nothing break this, the user might need
                // ctrl+C again, but it is acceptable, and rarely happened.
                // case 4: the ctrl+C is last, so we first change the prompt and then move to
                // cancel, it is expected behavior
                if (promptSwapPending) {
                    promptSwapPending = false;
                    boolean interruptWasSet = Thread.interrupted();
                    log.trace("Cleared prompt-swap interrupt: {}", interruptWasSet);
                    continue;
                }
                // Flag false ⇒ not a swap ⇒ genuine Ctrl+C → cancel the in-flight request/prompt,
                // or exit if idle (cancel returns null as the shutdown signal per IDLE ×
                // cancel).
                if (!cancelCommand(view)) {
                    break; // idle → exit the REPL
                }
                continue;
            } catch (EndOfFileException e) {
                break;
            }

            if (line == null || !running) break;
            line = normalizeInput(line, interaction);
            if (line.isEmpty()) continue;

            // A veto picker reply is resolved to an option name (by 1-based index or name) before
            // submit. An invalid choice re-renders the picker (the veto is still pending
            // server-side,
            // so the snapshot still shows PROMPTED + veto). Cancel-during-veto is handled by the
            // UserInterruptException catch above, which sends a refusal for that exact approval.
            if (activeVeto != null) {
                String resolved = resolveVetoChoice(activeVeto, line);
                if (resolved == null) {
                    renderer.println(theme.style(StyleToken.ERROR, "  invalid choice"));
                    continue;
                }
                line = resolved;
            }

            // Bind the reply to what the user actually saw. The atomic identity check discards
            // stale secrets and approval choices; ordinary command input remains a command even
            // if a new interaction arrived while readLine was returning.
            submitInput(line, interaction);
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────

    /** Text prompts preserve credential bytes; commands and approval choices ignore padding. */
    static @NonNull String normalizeInput(
            @NonNull String line, Frame.ServerFrame displayedInteraction) {
        return displayedInteraction instanceof Frame.Prompt ? line : line.trim();
    }

    /**
     * Renders a HITL veto as a numbered option picker above the prompt. Each option is shown as
     * {@code [n] NAME}; the user replies with the index or the option name (resolved by {@link
     * #resolveVetoChoice}).
     *
     * @param veto the pending veto payload
     */
    private void renderVetoPicker(Frame.@NonNull Veto veto) {
        renderer.println("");
        renderer.println(
                theme.style(StyleToken.BORDER, "  ╭─ HITL ")
                        + theme.style(StyleToken.ACCENT, veto.tool())
                        + theme.style(StyleToken.MUTED, "  (" + veto.scenario() + ")"));
        if (!veto.args().isEmpty()) {
            renderer.println("  │ " + theme.style(StyleToken.MUTED, "args: " + veto.args()));
        }
        int i = 1;
        for (String opt : veto.options()) {
            renderer.println("  │ " + theme.style(StyleToken.ACCENT, "[" + i + "]") + "  " + opt);
            i++;
        }
        renderer.println(theme.style(StyleToken.BORDER, "  ╰──"));
    }

    /**
     * Resolves a veto picker reply to an option name. Accepts a 1-based index into the offered
     * options or the option name (case-insensitive). Returns {@code null} if the reply matches no
     * offered option, so the caller can re-render the picker.
     *
     * @param veto the pending veto payload
     * @param reply the user's reply (index or name)
     * @return the resolved option name, or {@code null} if invalid
     */
    private String resolveVetoChoice(Frame.@NonNull Veto veto, @NonNull String reply) {
        List<String> options = veto.options();
        if (options.isEmpty()) {
            return null;
        }
        try {
            int idx = Integer.parseInt(reply.trim());
            if (idx >= 1 && idx <= options.size()) {
                return options.get(idx - 1);
            }
        } catch (NumberFormatException ignored) {
            // fall through to a name match
        }
        for (String opt : options) {
            if (opt.equalsIgnoreCase(reply.trim())) {
                return opt;
            }
        }
        return null;
    }

    /** Prints the startup ASCII banner header. */
    private void printBanner() {
        for (String line : HEADER.split("\n")) {
            renderer.println(theme.styleBold(StyleToken.ACCENT, line));
        }
        renderer.println(theme.style(StyleToken.MUTED, "  terminal " + VetoVersion.VERSION));
        renderer.println("");
    }

    /** An inbound interaction update wakes the REPL after recording its reason. */
    private void refreshPrompt() {
        if (Thread.currentThread() == mainThread) return;
        promptSwapPending = true;
        mainThread.interrupt();
    }

    /** Wake readLine after stopping, so shutdown cannot be mistaken for Ctrl+C. */
    private void terminate() {
        running = false;
        mainThread.interrupt();
    }

    // ── main ──────────────────────────────────────────────────────────────

    /**
     * Main entry point for starting the VetoTerminal client.
     *
     * @param args command line arguments, supports --debug or -d to enable log file output
     */
    static void main(@NonNull String @NonNull [] args) {
        System.setProperty("file.encoding", "UTF-8");

        ClientOptions options = ClientOptions.parse(args);
        Logging.configure(options.debug());

        // The workspace root is sent to the backend in the IPC Hello handshake and becomes the
        // agent's operational root. --workspace makes it explicit; otherwise it falls back to the
        // JVM cwd (user.dir) - i.e. wherever the terminal is launched from.
        String configuredWorkspace = options.workspace();
        String workspaceCwd =
                configuredWorkspace != null ? configuredWorkspace : System.getProperty("user.dir");
        if (configuredWorkspace != null && !Files.isDirectory(Path.of(configuredWorkspace))) {
            System.err.println("Workspace not a directory: " + configuredWorkspace);
            return;
        }
        if (workspaceCwd == null) {
            System.err.println("Cannot determine workspace: user.dir is not set");
            return;
        }

        org.jline.terminal.Terminal jt = null;
        try {
            // Build the system JLine Terminal and the Mordant Terminal.
            // Force the JNA NativeWinSysTerminal over the FFM one. On JDK 25 JLine 3.26.1 prefers
            // the FFM terminal, but it does not advertise the capabilities JLine's Status bar
            // requires (change_scroll_region / save_cursor / restore_cursor / cursor_address), so
            // Status.display stays null: the status bar never renders and Status.resize NPEs on
            // window-resize signals. The JNA terminal advertises those capabilities, so the status
            // bar works. JNA is already on the classpath (jna + mordant-jvm-jna).
            jt =
                    TerminalBuilder.builder()
                            .system(true)
                            .ffm(false)
                            .jna(true)
                            .encoding("UTF-8")
                            .build();
            Terminal mt = MordantTerminal.create();
            System.out.println("Connecting to backend at " + options.address() + " ...");
            try (var transport =
                    new ProtocolClient(
                            () -> TcpConnection.connect(options.address()),
                            VetoVersion.VERSION,
                            workspaceCwd)) {
                Version serverVersion = transport.serverProductVersion();
                System.out.println(
                        "Connected to veto-core "
                                + serverVersion
                                + " (veto-terminal "
                                + VetoVersion.VERSION
                                + ").");
                VetoTerminal vt = new VetoTerminal(mt, transport);
                Completer completer = new CommandCompleter(transport);
                LineReader r =
                        LineReaderBuilder.builder().terminal(jt).completer(completer).build();
                r.setVariable(LineReader.HISTORY_FILE, Path.of(".veto_history"));
                vt.start(r);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            System.err.println("Backend connection interrupted.");
        } catch (TimeoutException timeout) {
            System.err.println("Backend connection timed out.");
        } catch (ExecutionException failure) {
            var cause = failure.getCause();
            System.err.println(
                    "Backend connection failed: "
                            + (cause == null ? failure.getMessage() : cause.getMessage()));
            log.debug("Backend connection failed", failure);
        } catch (IOException e) {
            System.err.println("Terminal failed: " + e.getMessage());
        } finally {
            // Closing the JLine Terminal restores the original terminal mode (scroll region,
            // echo, etc.) and flushes pending output — ensuring the status bar is cleaned up
            // even on crash.  JLine's Status.close() can throw NPE on terminals that don't
            // support the status bar (display == null), so we catch that here.
            if (jt != null) {
                try {
                    jt.close();
                } catch (IOException | NullPointerException ignored) {
                    // Best-effort cleanup on the way out.
                }
            }
        }
    }
}
