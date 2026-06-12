package top.focess.veto.terminal;

import com.github.ajalt.mordant.terminal.Terminal;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jline.reader.*;
import org.jline.terminal.TerminalBuilder;
import top.focess.veto.contract.IpcFrame;
import top.focess.veto.contract.IpcMeta;

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
    private LineReader reader;
    private final ZmqClient client;
    private final MordantRenderer renderer;

    private String displayUser;
    private int turnCount;
    private volatile boolean running;

    private final Object requestLock = new Object();
    private volatile boolean requestInProgress = false;
    private volatile boolean firstDelta = true;
    private volatile IpcFrame.ServerFrame terminalFrame = null;

    public VetoTerminal(Terminal t, ZmqClient client) {
        this.t = t;
        this.client = client;
        this.renderer = new MordantRenderer(t);
    }

    public void start(LineReader reader) {
        this.reader = reader;
        printBanner();
        running = true;

        // --- heartbeat ---
        Thread heartbeat =
                new Thread(
                        () -> {
                            while (running && !Thread.currentThread().isInterrupted()) {
                                try {
                                    Thread.sleep(HEARTBEAT_INTERVAL_MS);
                                    if (!running) break;
                                    client.send(new IpcFrame.Heartbeat());
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
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
        Thread consumerThread = new Thread(this::incomingLoop, "veto-incoming");
        consumerThread.setDaemon(true);
        consumerThread.start();

        try {
            repl();
        } finally {
            running = false;
            hintWidgets.disable();
            heartbeat.interrupt();
            consumerThread.interrupt();
            client.send(new IpcFrame.Bye());
            client.close();
        }
    }

    // ── repl ──────────────────────────────────────────────────────────────

    private void repl() {
        while (running) {
            printHint();
            String line = reader.readLine(prompt());
            if (line == null) break;
            line = line.trim();
            if (line.isEmpty()) continue;

            if (!line.startsWith("/")) {
                echoInput(line);
                MordantTerminal.println(t, MordantTerminal.dim(t, "  thinking…"));
            }

            executeRequest(line);
        }
        MordantTerminal.println(t, "  Goodbye.");
    }

    private void executeRequest(String line) {
        synchronized (requestLock) {
            firstDelta = true;
            terminalFrame = null;
            requestInProgress = true;
            client.send(new IpcFrame.Request(line));
            long deadline = System.currentTimeMillis() + REQUEST_TIMEOUT_MS;
            while (requestInProgress) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    renderer.error("Request timed out.");
                    requestInProgress = false;
                    break;
                }
                try {
                    requestLock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void incomingLoop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                IpcFrame.ServerFrame frame = client.receive();
                if (frame == null) {
                    continue;
                }
                handleIncomingFrame(frame);
            } catch (Exception e) {
                if (running) {
                    log.log(Level.WARNING, "Error in incoming loop", e);
                }
            }
        }
    }

    private void handleIncomingFrame(IpcFrame.ServerFrame frame) {
        if (frame instanceof IpcFrame.Delta d) {
            log.fine("[DELTA] len=" + d.content().length());
            if (firstDelta) {
                MordantTerminal.println(t, "");
                firstDelta = false;
            }
            MordantTerminal.print(t, d.content());
        } else if (frame instanceof IpcFrame.Progress p) {
            MordantTerminal.println(t, MordantTerminal.dim(t, "  ⏳ " + p.content()));
        } else if (frame instanceof IpcFrame.Prompt prompt) {
            MordantTerminal.println(t, "");
            boolean mask = Boolean.TRUE.equals(prompt.meta().get(IpcMeta.MASK));
            String promptText = "  " + prompt.content() + " ";
            String reply;
            try {
                reply = mask ? reader.readLine(promptText, '\0') : reader.readLine(promptText);
            } catch (Exception e) {
                client.send(new IpcFrame.Cancel());
                reply = null;
            }
            if (reply != null) {
                if (reply.trim().isEmpty()) {
                    client.send(new IpcFrame.Input(""));
                } else {
                    client.send(new IpcFrame.Input(reply.trim()));
                }
            }
        } else if (frame instanceof IpcFrame.Done d) {
            if (!firstDelta) {
                MordantTerminal.println(t, "");
            }
            applySessionMeta(d.meta());
            if (Boolean.TRUE.equals(d.meta().get(IpcMeta.EXIT))) {
                running = false;
            }
            signalDone(d);
        } else if (frame instanceof IpcFrame.Error e) {
            if (!firstDelta) {
                MordantTerminal.println(t, "");
            }
            renderer.error(e.content());
            signalDone(e);
        }
    }

    private void signalDone(IpcFrame.ServerFrame frame) {
        synchronized (requestLock) {
            terminalFrame = frame;
            requestInProgress = false;
            requestLock.notifyAll();
        }
    }

    // ── session metadata ──────────────────────────────────────────────────

    private void applySessionMeta(Map<String, Object> meta) {
        if (meta.containsKey(IpcMeta.USERNAME)) displayUser = (String) meta.get(IpcMeta.USERNAME);
        if (Boolean.TRUE.equals(meta.get(IpcMeta.CLEAR_SESSION))) {
            displayUser = null;
            turnCount = 0;
        }
        if (meta.containsKey(IpcMeta.TURN_NUMBER))
            turnCount = ((Number) meta.get(IpcMeta.TURN_NUMBER)).intValue();
    }

    // ── UI helpers ────────────────────────────────────────────────────────

    private void echoInput(String line) {
        int termWidth = reader.getTerminal().getWidth();
        int maxWidth = termWidth > 0 ? termWidth - 4 : 76;
        int borderLen = Math.max(0, Math.min(maxWidth, line.length() + 4));
        MordantTerminal.println(t, "");
        MordantTerminal.println(t, MordantTerminal.dim(t, "  ╭─ you " + "─".repeat(borderLen)));
        MordantTerminal.println(t, "  │ " + line);
        MordantTerminal.println(
                t, MordantTerminal.dim(t, "  ╰" + "─".repeat(Math.min(maxWidth, borderLen + 4))));
        MordantTerminal.println(t, "");
    }

    private void printHint() {
        if (displayUser == null) {
            MordantTerminal.println(t, MordantTerminal.dim(t, "  /login to start | /help"));
        } else {
            MordantTerminal.println(
                    t, MordantTerminal.dim(t, "  " + displayUser + " | turns: " + turnCount));
        }
    }

    private String prompt() {
        return displayUser != null ? "▸ " : "◇ ";
    }

    private void printBanner() {
        for (String line : HEADER.split("\n"))
            MordantTerminal.println(t, MordantTerminal.bold(t, MordantTerminal.cyan(t, line)));
        MordantTerminal.println(t, MordantTerminal.dim(t, "  terminal v3.0"));
        MordantTerminal.println(t, "");
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
                            comp.description() != null && !comp.description().isBlank()
                                    ? comp.description()
                                    : null;
                    String group =
                            comp.group() != null && !comp.group().isBlank() ? comp.group() : null;
                    out.add(new Candidate(name, name, group, desc, null, null, true));
                }
            }
        }
    }

    // ── main ──────────────────────────────────────────────────────────────

    public static void main(String[] args) {
        System.setProperty("file.encoding", "UTF-8");
        java.util.logging.Logger.getLogger("org.jline").setLevel(Level.OFF);
        java.util.logging.Logger.getLogger("top.focess.veto.terminal").setLevel(Level.FINE);

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
