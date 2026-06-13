package top.focess.veto.terminal;

import com.github.ajalt.mordant.terminal.Terminal;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import org.jline.reader.*;
import org.jline.terminal.TerminalBuilder;
import top.focess.veto.contract.IpcFrame;

/**
 * Main interactive REPL terminal controller for Veto Core.
 *
 * <p>Handles user keystrokes, input reading via JLine {@link LineReader}, and coordinates with the
 * backend using ZeroMQ via {@link ZmqClient}. Manages prompt state changes, input/request queuing,
 * and updates the bottom status bar dynamically.
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

    private static final Logger log = Logger.getLogger(VetoTerminal.class.getName());

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

    private static final String BACKEND_ADDR = "tcp://127.0.0.1:5555";
    private static final long REQUEST_TIMEOUT_MS = 85_000;
    private static final long HEARTBEAT_INTERVAL_MS = 30_000;

    private final Terminal t;
    private final ZmqClient client;
    private LineReader reader;
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

    private TerminalStatus status;
    private volatile boolean running;
    private State state = State.IDLE;
    private State targetState = State.IDLE;
    private final Object stateLock = new Object();

    private IpcFrame.Prompt activePrompt = null;
    private Thread mainThread;

    /**
     * Constructs a new VetoTerminal instance.
     *
     * @param t the Mordant Terminal instance used for styled outputs
     * @param client the ZmqClient used for network IPC communications with the backend
     */
    public VetoTerminal(Terminal t, ZmqClient client) {
        this.t = t;
        this.client = client;
    }

    /**
     * Initializes the terminal components, starts background threads, enables UI widgets, and
     * enters the interactive REPL loop. Ensures clean resource teardown on shutdown.
     *
     * @param reader the JLine LineReader instance to read inputs from
     */
    public void start(LineReader reader) {
        this.reader = reader;
        this.renderer = new MordantRenderer(t, reader);
        this.mainThread = Thread.currentThread();
        this.status = new TerminalStatus(reader.getTerminal(), renderer);
        synchronized (stateLock) {
            this.status.refresh();
        }

        printBanner();
        running = true;

        // --- heartbeat thread ---
        // Periodically sends heartbeat messages to backend to maintain connection and session
        // presence.
        Thread heartbeat =
                new Thread(
                        () -> {
                            while (running) {
                                try {
                                    Thread.sleep(HEARTBEAT_INTERVAL_MS);
                                    client.send(new IpcFrame.Heartbeat());
                                } catch (Exception e) {
                                    if (running) {
                                        log.log(
                                                Level.WARNING,
                                                "Error in heartbeat loop, terminating thread",
                                                e);
                                    }
                                    break;
                                }
                            }
                        },
                        "zmq-hb");
        heartbeat.setDaemon(true);
        heartbeat.start();

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
                                        log.log(
                                                Level.WARNING,
                                                "Error in incoming loop, terminating thread",
                                                e);
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
            // Teardown sequence: stop loops, disable widgets, interrupt threads, clear status bar,
            // and close client.
            running = false;
            hintWidgets.disable();
            heartbeat.interrupt();
            consumerThread.interrupt();
            synchronized (stateLock) {
                status.clear();
            }
            client.send(new IpcFrame.Bye());
            try {
                // Sleep briefly to allow Bye packet to be flushed before socket close.
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
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
                    mask = Boolean.TRUE.equals(activePrompt.meta().get("mask")) ? '*' : null;
                } else {
                    // Standard prompt: green when logged in, red when logged out.
                    promptText =
                            status.getDisplayUser() != null
                                    ? renderer.green("▸ ")
                                    : renderer.red("◇ ");
                    mask = null;
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
                        state = State.IDLE;
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
        renderer.println("  Goodbye.");
    }

    /**
     * Enqueues a command for execution. If the terminal is currently idle, immediately dispatches
     * the request to the ZMQ client.
     *
     * @param line the command string to execute
     */
    private void executeRequest(String line) {
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
    private void handleFrame(IpcFrame.ServerFrame frame) {
        if (frame instanceof IpcFrame.Delta(String content)) {
            // Immediate print for stream response chunks.
            log.fine("[DELTA] len=" + content.length());
            renderer.println(content);
        } else if (frame instanceof IpcFrame.Progress p) {
            // Display background progress messages.
            String styled = renderer.dim("  ⏳ " + p.content());
            renderer.println(styled);
        } else {
            synchronized (stateLock) {
                if (frame instanceof IpcFrame.Prompt prompt) {
                    // Server requests additional input. Interrupt readLine to show the new prompt.
                    activePrompt = prompt;
                    targetState = State.PROMPTED;
                    state = State.PROGRAMMATIC_INTERRUPT;
                    mainThread.interrupt();
                } else if (frame
                        instanceof IpcFrame.Done(Map<String, Object> meta, String content)) {
                    if (content != null) {
                        renderer.println(content);
                    }
                    status.apply(meta);
                    dispatchNextOrIdle();
                    // Interrupt active readLine to transition state to the targetState.
                    state = State.PROGRAMMATIC_INTERRUPT;
                    mainThread.interrupt();
                } else if (frame instanceof IpcFrame.Terminate(String reason)) {
                    if (reason != null) {
                        renderer.println(reason);
                    }
                    running = false;
                    targetState = State.IDLE;
                    state = State.PROGRAMMATIC_INTERRUPT;
                    mainThread.interrupt();
                } else if (frame instanceof IpcFrame.Error e) {
                    if (e.content() != null) {
                        renderer.error(e.content());
                    }
                    dispatchNextOrIdle();
                    state = State.PROGRAMMATIC_INTERRUPT;
                    mainThread.interrupt();
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
    private void echoInput(String line) {
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

    /** Tab completer implementation that retrieves completion candidates from the backend. */
    private class VetoCompleter implements Completer {

        @Override
        public void complete(LineReader r, ParsedLine line, List<Candidate> out) {
            String fullLine = line.line();
            // Completion is only requested for slash commands.
            if (!fullLine.startsWith("/")) return;
            IpcFrame.CompleteResult compResult = client.complete(fullLine, 3, TimeUnit.SECONDS);
            if (compResult != null && compResult.candidates() != null) {
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
    public static void main(String[] args) {
        System.setProperty("file.encoding", "UTF-8");

        boolean debug = false;
        for (String arg : args) {
            if ("--debug".equals(arg) || "-d".equals(arg)) {
                debug = true;
                break;
            }
        }

        // Setup log file handler if debugging is enabled.
        if (debug) {
            try {
                FileHandler fileHandler = new FileHandler("veto_debug.log", true);
                fileHandler.setFormatter(new SimpleFormatter());
                fileHandler.setLevel(Level.FINE);

                Logger myLogger = Logger.getLogger("top.focess.veto");
                myLogger.addHandler(fileHandler);
                myLogger.setLevel(Level.FINE);
                myLogger.setUseParentHandlers(false);
            } catch (Exception ignored) {
            }
        }

        // Mute JLine internal logger to prevent console clutter.
        Logger.getLogger("org.jline").setLevel(Level.OFF);

        try {
            // Build the system JLine Terminal and the Mordant Terminal.
            org.jline.terminal.Terminal jt =
                    TerminalBuilder.builder().system(true).jna(true).encoding("UTF-8").build();
            Terminal mt = MordantTerminal.create();
            ZmqClient transport = new ZmqClient(BACKEND_ADDR);
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
