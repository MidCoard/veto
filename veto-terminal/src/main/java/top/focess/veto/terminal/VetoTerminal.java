package top.focess.veto.terminal;

import com.github.ajalt.mordant.terminal.Terminal;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
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
    private final ZmqTerminal transport;
    private final MordantRenderer renderer;
    private VetoHintWidgets hintWidgets;

    private String displayUser;
    private int turnCount;
    private volatile boolean busy;
    private volatile boolean running;

    /** Monotonic sequence number for correlating requests with responses. */
    private final AtomicLong nextSeq = new AtomicLong(1);

    public VetoTerminal(Terminal t, ZmqTerminal transport) {
        this.t = t;
        this.transport = transport;
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
                                    transport.send(new IpcFrame.Heartbeat());
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
        hintWidgets = new VetoHintWidgets(reader, transport);
        hintWidgets.enable();

        try {
            repl();
        } finally {
            running = false;
            hintWidgets.disable();
            heartbeat.interrupt();
            transport.send(new IpcFrame.Bye());
            transport.close();
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

            IpcFrame result = exchange(line);

            if (result instanceof IpcFrame.Done done) {
                applySessionMeta(done.meta());
                if (Boolean.TRUE.equals(done.meta().get(IpcMeta.EXIT))) break;
                MordantTerminal.println(t, "");
            } else if (result instanceof IpcFrame.Error err) {
                renderer.error(err.content());
            }
        }
        MordantTerminal.println(t, "  Goodbye.");
    }

    private IpcFrame exchange(String line) {
        busy = true;
        long seq = nextSeq.getAndIncrement();
        try {
            transport.send(new IpcFrame.Request(line, seq));

            long deadline = System.currentTimeMillis() + REQUEST_TIMEOUT_MS;
            boolean firstDelta = true;

            while (System.currentTimeMillis() < deadline) {
                IpcFrame frame = transport.receive();
                if (frame == null) {
                    return new IpcFrame.Error("No response from backend.");
                }

                switch (frame) {
                    case IpcFrame.Delta d -> {
                        log.fine("[DELTA] len=" + d.content().length());
                        if (firstDelta) {
                            MordantTerminal.println(t, "");
                            firstDelta = false;
                        }
                        MordantTerminal.print(t, d.content());
                    }

                    case IpcFrame.Progress p ->
                            MordantTerminal.println(
                                    t, MordantTerminal.dim(t, "  ⏳ " + p.content()));

                    case IpcFrame.Prompt prompt -> {
                        MordantTerminal.println(t, "");
                        boolean mask = Boolean.TRUE.equals(prompt.meta().get(IpcMeta.MASK));
                        String promptText = "  " + prompt.content() + " ";
                        String reply;
                        try {
                            reply =
                                    mask
                                            ? reader.readLine(promptText, '\0')
                                            : reader.readLine(promptText);
                        } catch (Exception e) {
                            return new IpcFrame.Error("Input cancelled.");
                        }
                        if (reply == null || reply.trim().isEmpty()) {
                            transport.send(new IpcFrame.Input(""));
                        } else {
                            transport.send(new IpcFrame.Input(reply.trim()));
                        }
                        deadline = System.currentTimeMillis() + REQUEST_TIMEOUT_MS;
                    }

                    case IpcFrame.Done d -> {
                        // Skip stale responses from a previous request
                        if (d.seq() != 0 && d.seq() != seq) continue;
                        if (!firstDelta) {
                            MordantTerminal.println(t, "");
                        }
                        return d;
                    }

                    case IpcFrame.Error e -> {
                        // Skip stale errors from a previous request
                        if (e.seq() != 0 && e.seq() != seq) continue;
                        if (!firstDelta) {
                            MordantTerminal.println(t, "");
                        }
                        return e;
                    }

                    default -> {}
                }
            }
            return new IpcFrame.Error("Request timed out.");
        } finally {
            busy = false;
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
            busy = true;
            long seq = nextSeq.getAndIncrement();
            try {
                transport.send(new IpcFrame.Complete(fullLine, seq));
                IpcFrame reply = transport.receive(3, java.util.concurrent.TimeUnit.SECONDS);
                if (reply instanceof IpcFrame.Done done
                        && done.content() != null
                        && (done.seq() == 0 || done.seq() == seq)) {
                    for (String entry : done.content().split("\n")) {
                        String trimmed = entry.trim();
                        if (trimmed.isEmpty()) continue;
                        String[] parts = trimmed.split("\t", 3);
                        String name = parts[0];
                        String desc = parts.length > 1 && !parts[1].isBlank() ? parts[1] : null;
                        String group = parts.length > 2 && !parts[2].isBlank() ? parts[2] : null;
                        out.add(new Candidate(name, name, group, desc, null, null, true));
                    }
                }
            } finally {
                busy = false;
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
            ZmqTerminal transport = new ZmqTerminal(BACKEND_ADDR);
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
