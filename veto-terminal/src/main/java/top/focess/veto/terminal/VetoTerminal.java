package top.focess.veto.terminal;

import com.github.ajalt.mordant.terminal.Terminal;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jline.reader.*;
import org.jline.terminal.TerminalBuilder;
import top.focess.veto.contract.HintInfo;
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
    private static final long REQUEST_TIMEOUT_MS = 120_000;
    private static final long HEARTBEAT_INTERVAL_MS = 30_000;

    private final Terminal t;
    private LineReader reader;
    private final ZmqTerminal transport;
    private final MordantRenderer renderer;

    private String displayUser;
    private int turnCount;
    private volatile boolean busy;
    private volatile boolean running;
    private int lastTipLen;

    public VetoTerminal(Terminal t, LineReader reader, ZmqTerminal transport) {
        this.t = t;
        this.reader = reader;
        this.transport = transport;
        this.renderer = new MordantRenderer(t);
    }

    public void start() {
        printBanner();
        running = true;

        Thread heartbeat =
                new Thread(
                        () -> {
                            while (running) {
                                try {
                                    Thread.sleep(HEARTBEAT_INTERVAL_MS);
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

        registerHintWidget();
        startHintReplyReader();

        try {
            repl();
        } finally {
            running = false;
            heartbeat.interrupt();
            transport.send(new IpcFrame.Bye());
            transport.close();
        }
    }

    // ── space → send hint ────────────────────────────────────────────────

    private void registerHintWidget() {
        Widget hintSender =
                () -> {
                    reader.getBuffer().write(" ");
                    String buf = reader.getBuffer().toString();
                    if (buf.startsWith("/") && buf.indexOf(' ') > 1) {
                        log.fine("HINT send: " + buf);
                        transport.send(new IpcFrame.Hint(buf));
                    }
                    return true;
                };
        reader.getWidgets().put("veto-hint-sender", hintSender);
        reader.getKeyMaps().get(LineReader.MAIN).bind(new Reference("veto-hint-sender"), " ");
    }

    // ── hint reply → write ghost text, cursor back ───────────────────────

    private void startHintReplyReader() {
        Thread reply =
                new Thread(
                        () -> {
                            while (running) {
                                if (busy) {
                                    try {
                                        Thread.sleep(100);
                                    } catch (InterruptedException e) {
                                        break;
                                    }
                                    continue;
                                }
                                IpcFrame f = transport.tryReceive();
                                if (f instanceof IpcFrame.Done done) {
                                    String text = done.content();
                                    String ghost;
                                    if (text != null && !text.isBlank()) {
                                        String desc = (String) done.meta().get("description");
                                        HintInfo hint = new HintInfo(text, desc);
                                        ghost =
                                                MordantTerminal.dim(
                                                        VetoTerminal.this.t, hint.displayText());
                                    } else {
                                        ghost = "";
                                    }
                                    try {
                                        var w = VetoTerminal.this.reader.getTerminal().writer();
                                        // Erase previous ghost text
                                        if (lastTipLen > 0) {
                                            w.print("\033[" + lastTipLen + "X");
                                        }
                                        // Write new ghost text, then jump
                                        // back
                                        if (!ghost.isEmpty()) {
                                            w.print(ghost);
                                            w.print("\033[" + ghost.length() + "D");
                                        }
                                        w.flush();
                                        lastTipLen = ghost.length();
                                    } catch (Exception ignored) {
                                    }
                                }
                                try {
                                    Thread.sleep(50);
                                } catch (InterruptedException e) {
                                    break;
                                }
                            }
                        },
                        "hint-reply");
        reply.setDaemon(true);
        reply.start();
    }

    // ── repl ──────────────────────────────────────────────────────────────

    private void repl() {
        while (running) {
            printHint();
            String line = reader.readLine(prompt());
            if (line == null) break;
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.equals("/exit") || line.equals("/quit")) {
                MordantTerminal.println(t, "  Goodbye.");
                break;
            }

            if (!line.startsWith("/")) {
                echoInput(line);
                MordantTerminal.println(t, MordantTerminal.dim(t, "  thinking…"));
            }

            IpcFrame result = exchange(line);

            if (result instanceof IpcFrame.Done done) {
                applySessionMeta(done.meta());
                if (Boolean.TRUE.equals(done.meta().get("exit"))) break;
                MordantTerminal.println(t, "");
            } else if (result instanceof IpcFrame.Error err) {
                renderer.error(err.content());
            }
        }
        MordantTerminal.println(t, "  Goodbye.");
    }

    private IpcFrame exchange(String line) {
        busy = true;
        try {
            transport.send(new IpcFrame.Request(line));

            long deadline = System.currentTimeMillis() + REQUEST_TIMEOUT_MS;
            boolean firstDelta = true;

            while (System.currentTimeMillis() < deadline) {
                IpcFrame frame = transport.receive();
                if (frame == null) {
                    return new IpcFrame.Error("No response from backend.");
                }

                switch (frame) {
                    case IpcFrame.Delta d -> {
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
                        boolean mask = Boolean.TRUE.equals(prompt.meta().get("mask"));
                        String promptText = "  " + prompt.content() + " ";
                        String reply;
                        try {
                            reply =
                                    mask
                                            ? reader.readLine(promptText, '*')
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
                        if (!firstDelta) MordantTerminal.println(t, "");
                        return d;
                    }

                    case IpcFrame.Error e -> {
                        if (!firstDelta) MordantTerminal.println(t, "");
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
        if (meta.containsKey("username")) displayUser = (String) meta.get("username");
        if (Boolean.TRUE.equals(meta.get("clearSession"))) {
            displayUser = null;
            turnCount = 0;
        }
        if (meta.containsKey("turnNumber"))
            turnCount = ((Number) meta.get("turnNumber")).intValue();
    }

    // ── UI helpers ────────────────────────────────────────────────────────

    private void echoInput(String line) {
        MordantTerminal.println(t, "");
        MordantTerminal.println(
                t,
                MordantTerminal.dim(
                        t, "  ╭─ you " + "─".repeat(Math.max(0, Math.min(line.length(), 58)))));
        MordantTerminal.println(t, "  │ " + line);
        MordantTerminal.println(t, MordantTerminal.dim(t, "  ╰" + "─".repeat(62)));
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

    private static class VetoCompleter implements Completer {
        private final ZmqTerminal transport;

        VetoCompleter(ZmqTerminal transport) {
            this.transport = transport;
        }

        @Override
        public void complete(LineReader r, ParsedLine line, List<Candidate> out) {
            String fullLine = line.line();
            if (!fullLine.startsWith("/")) return;
            transport.send(new IpcFrame.Complete(fullLine));
            IpcFrame reply = transport.receive();
            if (reply instanceof IpcFrame.Done done && done.content() != null) {
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
            VetoTerminal vt = new VetoTerminal(mt, null, transport);
            VetoCompleter completer = new VetoCompleter(transport);
            LineReader r = LineReaderBuilder.builder().terminal(jt).completer(completer).build();
            vt.reader = r;
            vt.start();
        } catch (IOException e) {
            System.err.println("Terminal failed: " + e.getMessage());
        }
    }
}
