package top.focess.veto.terminal;

import com.github.ajalt.mordant.terminal.Terminal;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.NotNull;
import org.jline.reader.*;
import org.jline.terminal.TerminalBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.contract.ClientOptions;
import top.focess.veto.contract.IpcClient;
import top.focess.veto.contract.IpcFrame;

/**
 * Main interactive REPL terminal controller for Veto Core.
 *
 * <p>Handles user keystrokes, input reading via JLine {@link LineReader}, and coordinates with the
 * backend via {@link IpcClient}. Manages prompt state changes, input/request queuing, and updates
 * the bottom status bar dynamically.
 *
 * <h3>Concurrency &amp; Synchronization model</h3>
 *
 * All mutable REPL state variables (such as {@link #state}, {@link #targetState}, and {@link
 * #activePrompt}) are protected by {@link #stateLock}. The pending-request queue lives inside
 * {@link TerminalStatus} but is likewise accessed only while holding {@code stateLock}. Since
 * status bar updates and prompt configuration occur across different threads (main REPL thread,
 * background heartbeat/consumer threads), synchronization ensures consistency and thread safety.
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

    /** ANSI renderer delegating to Mordant for colour/style formatting. */
    private MordantRenderer renderer;

    /** States representing the interactive status of the terminal REPL. */
    public enum State {
        /** Terminal is idle and ready for a new user command. */
        IDLE,
        /** Terminal has dispatched a request and is awaiting a response from the backend. */
        AWAITING_RESPONSE,
        /** Terminal has been prompted by the server to provide additional input. */
        PROMPTED,
        /**
         * Terminal is undergoing a state transition triggered by an asynchronous background frame.
         */
        PROGRAMMATIC_INTERRUPT
    }

    /** Current session status bar manager; updated on each REPL iteration. */
    private TerminalStatus status;

    /** Set to {@code false} to signal all background threads to stop. */
    private volatile boolean running;

    /** Current REPL interaction state. Guarded by {@link #stateLock}. */
    private State state = State.IDLE;

    /**
     * Target state to transition into after the current programmatic interrupt is acknowledged.
     * Guarded by {@link #stateLock}.
     */
    private State targetState = State.IDLE;

    /** Lock object protecting {@link #state}, {@link #targetState}, and {@link #activePrompt}. */
    private final Object stateLock = new Object();

    /**
     * The {@link IpcFrame.Prompt} currently being presented to the user, or {@code null} when the
     * terminal is not in the {@link State#PROMPTED} state.
     */
    private IpcFrame.Prompt activePrompt = null;

    /**
     * Reference to the main REPL thread ({@link #repl()}) so that background threads can interrupt
     * its blocking {@link LineReader#readLine} call for state transitions.
     */
    private Thread mainThread;

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
     * Initializes the terminal components, starts background threads, enables UI widgets, and
     * enters the interactive REPL loop. Ensures clean resource teardown on shutdown.
     *
     * @param reader the JLine LineReader instance to read inputs from
     */
    public void start(@NotNull LineReader reader) {
        this.reader = reader;
        this.renderer = new MordantRenderer(t, reader);
        this.mainThread = Thread.currentThread();
        this.status = new TerminalStatus(reader.getTerminal(), renderer);
        synchronized (stateLock) {
            this.status.refresh();
        }

        printBanner();
        running = true;

        // Heartbeats are sent by the IpcClient itself (its ipc-hb thread).

        // --- hint widgets ---
        // Binds custom parameter autocomplete / tail-tip widgets to JLine reader.
        VetoHintWidgets hintWidgets = new VetoHintWidgets(reader, client);
        hintWidgets.enable();

        // --- incoming consumer thread ---
        // Reads frames from ZMQ connection and processes them asynchronously via handleFrame.
        Thread consumerThread =
                new Thread(
                        () -> {
                            while (running) {
                                try {
                                    IpcFrame.ServerFrame frame = client.receive();
                                    if (frame == null) {
                                        continue;
                                    }
                                    handleFrame(frame);
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
            synchronized (stateLock) {
                status.clear();
            }
            client.close();
        }
    }

    // ── repl ──────────────────────────────────────────────────────────────

    /**
     * The main interactive REPL read-eval-print loop. Continually displays the prompt, reads user
     * input, handles cancellation signals (Ctrl+C), and submits commands for execution.
     */
    private void repl() {
        while (running) {
            String line;
            String promptText;
            Character mask;
            synchronized (stateLock) {
                // Refresh bottom status bar session information.
                status.refresh();
                if (state == State.PROMPTED && activePrompt != null) {
                    // Display server-prompted input request with yellow indicator.
                    promptText =
                            renderer.yellow("▸")
                                    + " "
                                    + renderer.cyan(activePrompt.content())
                                    + " ";
                    mask = activePrompt.mask() ? '*' : null;
                    reader.setVariable(LineReader.DISABLE_HISTORY, Boolean.TRUE);
                } else {
                    // Standard prompt: green when logged in, red when logged out.
                    promptText =
                            status.getDisplayUser() != null
                                    ? renderer.green("▸ ")
                                    : renderer.red("◇ ");
                    mask = null;
                    reader.setVariable(LineReader.DISABLE_HISTORY, Boolean.FALSE);
                }
            }
            try {
                // Read a line of user input, blocking the thread.
                line = reader.readLine(promptText, mask);
            } catch (UserInterruptException e) {
                // Triggered when user presses Ctrl+C in JLine.
                synchronized (stateLock) {
                    if (state == State.PROGRAMMATIC_INTERRUPT) {
                        // The user interrupt was actually simulated by the background thread
                        // to break the blocking readLine call for a state transition.
                        state = targetState;
                        if (!running) {
                            break;
                        }
                        continue;
                    }
                    if (state == State.AWAITING_RESPONSE || state == State.PROMPTED) {
                        // True user Ctrl+C cancels the active request or prompt.
                        client.send(new IpcFrame.Cancel());
                        activePrompt = null;
                        status.getRequestQueue().clear();
                        state = State.AWAITING_RESPONSE;
                        continue;
                    }
                    if (state == State.IDLE) {
                        // Pressing Ctrl+C when idle exits the REPL.
                        break;
                    }
                    throw new RuntimeException("Unexpected interrupt in state " + state, e);
                }
            } catch (EndOfFileException e) {
                // Triggered when user sends EOF (e.g., Ctrl+D).
                break;
            }

            if (line == null) break;
            line = line.trim();

            synchronized (stateLock) {
                if (state == State.PROMPTED) {
                    // Submit prompt reply directly to backend as Input.
                    state = State.AWAITING_RESPONSE;
                    client.send(new IpcFrame.Input(line));
                    activePrompt = null;
                    continue;
                }
            }

            if (line.isEmpty()) continue;

            // Echo input inside a styled visual frame if it's a regular command (not a slash
            // command).
            if (!line.startsWith("/")) {
                echoInput(line);
                renderer.println(renderer.dim("  thinking…"));
            }

            executeRequest(line);
        }
    }

    /**
     * Enqueues a command for execution. If the terminal is currently idle, immediately dispatches
     * the request to the ZMQ client.
     *
     * @param line the command string to execute
     */
    private void executeRequest(@NotNull String line) {
        synchronized (stateLock) {
            status.getRequestQueue().add(line);
            if (state == State.IDLE) {
                // Immediately dispatch if idle rather than waiting for the next REPL iteration.
                String nextReq = status.getRequestQueue().removeFirst();
                state = State.AWAITING_RESPONSE;
                client.send(new IpcFrame.Request(nextReq));
            }
        }
    }

    /**
     * Handles an incoming ServerFrame received asynchronously from the backend.
     *
     * @param frame the incoming frame
     */
    private void handleFrame(@NotNull IpcFrame.ServerFrame frame) {
        if (frame instanceof IpcFrame.Delta(String content)) {
            // Immediate print for stream response chunks.
            log.debug("[DELTA] len={}", content.length());
            renderer.println(content);
        } else if (frame instanceof IpcFrame.Progress p) {
            // Display background progress messages.
            String styled = renderer.dim("  ⏳ " + p.content());
            renderer.println(styled);
        } else {
            synchronized (stateLock) {
                switch (frame) {
                    case IpcFrame.Prompt prompt -> {
                        // Server requests additional input. Interrupt readLine to show the new
                        // prompt.
                        activePrompt = prompt;
                        targetState = State.PROMPTED;
                        state = State.PROGRAMMATIC_INTERRUPT;
                        mainThread.interrupt();
                    }
                    case IpcFrame.Done(Map<String, Object> meta, String content) -> {
                        if (content != null) {
                            renderer.println(content);
                        }
                        status.apply(meta);
                        dispatchNextOrIdle();
                        // Interrupt active readLine to transition state to the targetState.
                        state = State.PROGRAMMATIC_INTERRUPT;
                        mainThread.interrupt();
                    }
                    case IpcFrame.Terminate(String reason) -> {
                        if (reason != null) {
                            renderer.println(reason);
                        }
                        running = false;
                        targetState = State.IDLE;
                        state = State.PROGRAMMATIC_INTERRUPT;
                        mainThread.interrupt();
                    }
                    case IpcFrame.Error e -> {
                        renderer.error(e.content());
                        dispatchNextOrIdle();
                        state = State.PROGRAMMATIC_INTERRUPT;
                        mainThread.interrupt();
                    }
                    default -> {}
                }
            }
        }
    }

    /**
     * Dequeues and dispatches the next request if one is queued, transitioning state to
     * AWAITING_RESPONSE. Otherwise, sets targetState to IDLE.
     *
     * <p>Note: Must be called while holding {@code stateLock}.
     */
    private void dispatchNextOrIdle() {
        if (!status.getRequestQueue().isEmpty()) {
            String nextReq = status.getRequestQueue().removeFirst();
            client.send(new IpcFrame.Request(nextReq));
            targetState = State.AWAITING_RESPONSE;
        } else {
            targetState = State.IDLE;
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
        renderer.println(renderer.dim("  ╭─ you " + "─".repeat(borderLen)));
        renderer.println("  │ " + line);
        renderer.println(renderer.dim("  ╰" + "─".repeat(Math.min(maxWidth, borderLen + 4))));
    }

    /** Prints the startup ASCII banner header. */
    private void printBanner() {
        for (String line : HEADER.split("\n")) renderer.println(renderer.bold(renderer.cyan(line)));
        renderer.println(renderer.dim("  terminal v3.0"));
        renderer.println("");
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
