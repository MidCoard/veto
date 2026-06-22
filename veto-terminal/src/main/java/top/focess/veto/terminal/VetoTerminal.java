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
 * <p>When a {@link IpcFrame.Prompt} arrives mid-{@code readLine}, the consumer thread (via {@link
 * TerminalView#onPrompt}) sets {@link #promptSwapPending} and interrupts the main thread to break
 * the blocking read. The main thread's {@code UserInterruptException} catch uses {@code
 * promptSwapPending} to distinguish that simulated swap from a genuine Ctrl+C — so a real Ctrl+C
 * while prompted still cancels (rather than being misread as a re-render). A stale interrupt flag
 * from a swap that landed during {@code submit} (not {@code readLine}) is cleared before the next
 * read so it doesn't spuriously throw.
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
     * Set by the consumer thread (via {@link TerminalView#onPrompt}) to break the main thread's
     * blocking {@code readLine} for the genuine mid-line Prompt swap. Read and cleared by the main
     * thread to distinguish the swap from a genuine Ctrl+C.
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
            ClientSession.State state = session.state();
            IpcFrame.Prompt activePrompt = session.activePrompt();

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
                boolean loggedIn = session.snapshot().username() != null;
                promptText =
                        loggedIn
                                ? theme.style(StyleToken.SUCCESS, "▸ ")
                                : theme.style(StyleToken.ERROR, "◇ ");
                mask = null;
                reader.setVariable(LineReader.DISABLE_HISTORY, Boolean.FALSE);
            }

            // A Prompt-swap interrupt may have been requested by the consumer thread since the
            // last readLine landed outside readLine (during submit). Clear the stale flag so this
            // readLine blocks cleanly; a genuine Ctrl+C is raised by JLine during readLine, never
            // queued between calls.
            if (promptSwapPending) {
                promptSwapPending = false;
                Thread.interrupted();
            }

            String line;
            try {
                line = reader.readLine(promptText, mask);
            } catch (UserInterruptException e) {
                if (promptSwapPending) {
                    // The consumer simulated the interrupt to break readLine for a Prompt swap —
                    // re-render with the new (PROMPTED) state.
                    promptSwapPending = false;
                    Thread.interrupted();
                    continue;
                }
                // Genuine Ctrl+C → cancel the in-flight request / prompt (or exit if idle).
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

            if (state == ClientSession.State.PROMPTED) {
                // Reply to the server prompt as an Input frame.
                IpcFrame.ClientFrame reply = session.submit(line);
                if (reply != null) {
                    client.send(reply);
                }
                continue;
            }

            // Echo input inside a styled visual frame if it's a regular command (not a slash
            // command).
            if (!line.startsWith("/")) {
                echoInput(line);
                renderer.println(theme.style(StyleToken.MUTED, "  thinking…"));
            }

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
            // The session already set state=PROMPTED; break the main thread's readLine so it
            // re-renders the prompted prompt. This is the one retained interrupt.
            promptSwapPending = true;
            mainThread.interrupt();
        }

        @Override
        public void onDone(String content) {
            if (content != null) {
                renderer.println(content);
            }
            // No status refresh here — onMetaChanged / onAwaiting / onIdle (which follow) refresh
            // it.
        }

        @Override
        public void onError(@NotNull StyledText content) {
            renderer.println(theme.style(content.token(), content.text()));
        }

        @Override
        public void onTerminate(@NotNull StyledText content) {
            renderer.println(theme.style(content.token(), content.text()));
            running = false;
        }

        @Override
        public void onIdle() {
            status.refresh();
        }

        @Override
        public void onAwaiting() {
            status.refresh();
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
            IpcFrame.CompleteResult compResult = client.complete(fullLine, 3, TimeUnit.SECONDS);
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
