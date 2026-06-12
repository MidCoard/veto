package top.focess.veto.terminal;

import com.github.ajalt.mordant.terminal.Terminal;
import java.io.IOException;
import java.nio.file.Path;
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

public class VetoTerminal {

    private static final Logger log = Logger.getLogger(VetoTerminal.class.getName());

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

    public enum State {
        IDLE,
        AWAITING_RESPONSE,
        PROMPTED,
        PROGRAMMATIC_INTERRUPT
    }

    private TerminalStatus status;
    private volatile boolean running;
    private State state = State.IDLE;
    private State targetState = State.IDLE;
    private final Object stateLock = new Object();
    private final List<String> requestQueue = new java.util.ArrayList<>();

    private IpcFrame.Prompt activePrompt = null;
    private Thread mainThread;

    public VetoTerminal(Terminal t, ZmqClient client) {
        this.t = t;
        this.client = client;
    }

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

        // --- heartbeat ---
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
        VetoHintWidgets hintWidgets = new VetoHintWidgets(reader, client);
        hintWidgets.enable();

        // --- incoming consumer thread ---
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
            repl();
        } finally {
            running = false;
            hintWidgets.disable();
            heartbeat.interrupt();
            consumerThread.interrupt();
            synchronized (stateLock) {
                status.clear();
            }
            client.send(new IpcFrame.Bye());
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
            }
            client.close();
        }
    }

    // ── repl ──────────────────────────────────────────────────────────────

    private void repl() {
        while (running) {
            String line;
            String promptText;
            Character mask;
            synchronized (stateLock) {
                status.refresh();
                if (state == State.PROMPTED && activePrompt != null) {
                    promptText =
                            renderer.yellow("▸")
                                    + " "
                                    + renderer.cyan(activePrompt.content())
                                    + " ";
                    mask = Boolean.TRUE.equals(activePrompt.meta().get("mask")) ? '*' : null;
                } else {
                    promptText =
                            status.getDisplayUser() != null
                                    ? renderer.green("▸ ")
                                    : renderer.red("◇ ");
                    mask = null;
                }
            }
            try {
                line = reader.readLine(promptText, mask);
            } catch (UserInterruptException e) {
                synchronized (stateLock) {
                    if (state == State.PROGRAMMATIC_INTERRUPT) {
                        state = targetState;
                        if (!running) {
                            break;
                        }
                        continue;
                    }
                    if (state == State.AWAITING_RESPONSE || state == State.PROMPTED) {
                        client.send(new IpcFrame.Cancel());
                        activePrompt = null;
                        requestQueue.clear();
                        state = State.IDLE;
                        continue;
                    } else {
                        break;
                    }
                }
            } catch (EndOfFileException e) {
                break;
            }

            if (line == null) break;
            line = line.trim();

            synchronized (stateLock) {
                if (state == State.PROMPTED) {
                    state = State.AWAITING_RESPONSE;
                    client.send(new IpcFrame.Input(line));
                    activePrompt = null;
                    continue;
                }
            }

            if (line.isEmpty()) continue;

            if (!line.startsWith("/")) {
                echoInput(line);
                renderer.println(renderer.dim("  thinking…"));
            }

            executeRequest(line);
        }
        renderer.println("  Goodbye.");
    }

    private void executeRequest(String line) {
        synchronized (stateLock) {
            requestQueue.add(line);
            if (state == State.IDLE) {
                String nextReq = requestQueue.removeFirst();
                state = State.AWAITING_RESPONSE;
                client.send(new IpcFrame.Request(nextReq));
            }
        }
    }

    private void handleFrame(IpcFrame.ServerFrame frame) {
        if (frame instanceof IpcFrame.Delta(String content)) {
            log.fine("[DELTA] len=" + content.length());
            renderer.println(content);
        } else if (frame instanceof IpcFrame.Progress p) {
            String styled = renderer.dim("  ⏳ " + p.content());
            renderer.println(styled);
        } else {
            synchronized (stateLock) {
                if (frame instanceof IpcFrame.Prompt prompt) {
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
                    if (!requestQueue.isEmpty()) {
                        String nextReq = requestQueue.removeFirst();
                        client.send(new IpcFrame.Request(nextReq));
                        targetState = State.AWAITING_RESPONSE;
                    } else {
                        targetState = State.IDLE;
                    }
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
                    if (!requestQueue.isEmpty()) {
                        String nextReq = requestQueue.removeFirst();
                        client.send(new IpcFrame.Request(nextReq));
                        targetState = State.AWAITING_RESPONSE;
                    } else {
                        targetState = State.IDLE;
                    }
                    state = State.PROGRAMMATIC_INTERRUPT;
                    mainThread.interrupt();
                }
            }
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────

    private void echoInput(String line) {
        int termWidth = reader.getTerminal().getWidth();
        int maxWidth = termWidth > 0 ? termWidth - 4 : 76;
        int borderLen = Math.max(0, Math.min(maxWidth, line.length() + 4));
        renderer.println("");
        renderer.println(renderer.dim("  ╭─ you " + "─".repeat(borderLen)));
        renderer.println("  │ " + line);
        renderer.println(renderer.dim("  ╰" + "─".repeat(Math.min(maxWidth, borderLen + 4))));
    }

    private void printBanner() {
        for (String line : HEADER.split("\n")) renderer.println(renderer.bold(renderer.cyan(line)));
        renderer.println(renderer.dim("  terminal v3.0"));
        renderer.println("");
    }

    // ── tab completion ───────────────────────────────────────────────────

    private class VetoCompleter implements Completer {

        @Override
        public void complete(LineReader r, ParsedLine line, List<Candidate> out) {
            String fullLine = line.line();
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
    public static void main(String[] args) {
        System.setProperty("file.encoding", "UTF-8");

        boolean debug = false;
        for (String arg : args) {
            if ("--debug".equals(arg) || "-d".equals(arg)) {
                debug = true;
                break;
            }
        }

        if (debug) {
            try {
                FileHandler fileHandler = new FileHandler("veto_debug.log", true);
                fileHandler.setFormatter(new SimpleFormatter());
                fileHandler.setLevel(Level.FINE);

                Logger myLogger = Logger.getLogger("top.focess.veto");
                myLogger.addHandler(fileHandler);
                myLogger.setLevel(Level.FINE);
                myLogger.setUseParentHandlers(false); // Do not print to console
            } catch (Exception ignored) {
            }
        }

        Logger.getLogger("org.jline").setLevel(Level.OFF);

        try {
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
