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

    /** Visible width (columns) of the currently drawn ghost hint. Guarded by {@link #ghostLock}. */
    private int lastTipLen;

    /** Serializes ghost-hint terminal writes against accept/completion clears. */
    private final Object ghostLock = new Object();

    /** Last buffer content we sent a hint for. Used to detect state changes. */
    private String lastBufferForHint = "";

    /** Monotonic sequence number for correlating requests with responses. */
    private long nextSeq = 1;

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

        registerBufferWatcher();
        registerAcceptWidget();
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

    // ── buffer watcher → send hint when needed ──────────────────────────
    //
    // Monitor buffer state and send hints when:
    // - User types space after a command (e.g. "/login ")
    // - User backspaces back to space state (e.g. "/login arg" → "/login ")
    //
    // This is simpler than intercepting keystrokes: we just poll the buffer
    // state in the hint reply reader and send a hint when it changes to
    // "command + space(s)" form.

    private void registerBufferWatcher() {}

    /**
     * Check if buffer warrants a hint. Returns true if: - Starts with "/" - Has at least one space
     * after command (e.g. "/login " or "/cmd arg ") - Ends with whitespace (user just finished
     * typing an arg)
     */
    private boolean shouldSendHint(String buf) {
        if (buf == null || buf.length() < 3) return false;
        if (!buf.startsWith("/")) return false;
        // Only send hint if the last character is whitespace
        if (!Character.isWhitespace(buf.charAt(buf.length() - 1))) return false;
        // Ensure there is at least one space separating command from possible arguments
        int spaceIdx = buf.indexOf(' ');
        return spaceIdx > 1;
    }

    // ── hint reply → inline ghost text ───────────────────────────────────
    //
    // The backend answers a Hint frame with a Done whose content is the
    // placeholder (e.g. "[user] [pass]"). We render it as dim "ghost" text to
    // the right of the cursor, then move the cursor back so typing flows over
    // it. All cursor math MUST use the *visible* width of the text — never the
    // styled string length, which carries invisible ANSI escape bytes.

    private void startHintReplyReader() {
        Thread reply =
                new Thread(
                        () -> {
                            while (running) {
                                if (busy) {
                                    sleepQuiet(50);
                                    continue;
                                }

                                // Check if the buffer changed to a state that needs a hint
                                long hintSeq = nextSeq;
                                try {
                                    String currentBuffer = reader.getBuffer().toString();
                                    boolean currentNeedsHint = shouldSendHint(currentBuffer);

                                    // Send hint if buffer changed
                                    if (!currentBuffer.equals(lastBufferForHint)) {
                                        eraseGhostToEol();
                                        if (currentNeedsHint) {
                                            // Buffer now ends with space → send the real hint
                                            log.fine("HINT send: " + currentBuffer);
                                            transport.send(
                                                    new IpcFrame.Hint(currentBuffer, hintSeq));
                                            nextSeq++;
                                        }
                                        lastBufferForHint = currentBuffer;
                                    }
                                } catch (Exception ignored) {
                                    // Buffer may be accessed from reader thread, ignore errors
                                }

                                IpcFrame.Done done = transport.tryReceiveHint();
                                if (done != null) {
                                    // Hint responses are advisory — the shouldSendHint check
                                    // below already validates whether the ghost is still relevant.
                                    if (shouldSendHint(reader.getBuffer().toString())) {
                                        drawGhost(done);
                                    }
                                }
                                sleepQuiet(30);
                            }
                        },
                        "hint-reply");
        reply.setDaemon(true);
        reply.start();
    }

    /** Render (or clear) the inline ghost hint described by a backend Done frame. */
    private void drawGhost(IpcFrame.Done done) {
        String placeholder = done.content();
        String plain;
        if (placeholder != null && !placeholder.isBlank()) {
            String desc = (String) done.meta().get(IpcMeta.DESCRIPTION);
            plain = new HintInfo(placeholder, desc).displayText();
        } else {
            plain = "";
        }
        if (plain == null) plain = "";

        synchronized (ghostLock) {
            try {
                var term = reader.getTerminal();
                var w = term.writer();
                boolean ansi = MordantTerminal.supportsAnsi(t);

                // Clamp to the columns left on the line so the ghost never
                // wraps — wrapping would invalidate the cursor restore below.
                // The prompt occupies a fixed 2 columns.
                int width = term.getWidth() > 0 ? term.getWidth() : 80;
                int cursorCol = 2 + reader.getBuffer().cursor();
                int avail = Math.max(0, width - cursorCol - 1);
                if (plain.length() > avail) {
                    plain = avail <= 1 ? "" : plain.substring(0, avail - 1) + "…";
                }

                // Erase the previously drawn ghost.
                if (lastTipLen > 0) {
                    w.print("\033[K"); // EL — erase to end of line
                }
                if (!plain.isEmpty() && ansi) {
                    w.print(MordantTerminal.dim(t, plain)); // styled (variable bytes)
                    w.print("\033[" + plain.length() + "D"); // restore by VISIBLE width
                    lastTipLen = plain.length();
                } else {
                    lastTipLen = 0;
                }
                w.flush();
            } catch (Exception ignored) {
            }
        }
    }

    /** Erase the ghost hint just before a line is accepted (runs on the reader thread). */
    private void clearGhostAtAccept() {
        synchronized (ghostLock) {
            if (lastTipLen <= 0) return;
            try {
                reader.callWidget(LineReader.END_OF_LINE);
                var w = reader.getTerminal().writer();
                w.print("\033[K"); // erase to end of line — drops trailing ghost
                w.flush();
            } catch (Exception ignored) {
            } finally {
                lastTipLen = 0;
            }
        }
    }

    /** Erase the ghost hint to end of line from the current cursor (e.g. before completion). */
    private void eraseGhostToEol() {
        synchronized (ghostLock) {
            if (lastTipLen <= 0) return;
            try {
                var w = reader.getTerminal().writer();
                w.print("\033[K");
                w.flush();
            } catch (Exception ignored) {
            } finally {
                lastTipLen = 0;
            }
        }
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── accept-line → clear ghost before submit ──────────────────────────

    private void registerAcceptWidget() {
        Widget accept =
                () -> {
                    clearGhostAtAccept();
                    reader.callWidget(LineReader.ACCEPT_LINE);
                    return true;
                };
        reader.getWidgets().put("veto-accept-line", accept);
        reader.getKeyMaps().get(LineReader.MAIN).bind(new Reference("veto-accept-line"), "\r");
        reader.getKeyMaps().get(LineReader.MAIN).bind(new Reference("veto-accept-line"), "\n");
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
        long seq = nextSeq++;
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
                        System.err.println(
                                "[DELTA] len=" + d.content().length() + " seq=" + d.index());
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
                        // Skip stale responses from a previous request
                        if (d.seq() != 0 && d.seq() != seq) {
                            System.err.println(
                                    "[DONE] stale seq="
                                            + d.seq()
                                            + " expected="
                                            + seq
                                            + " — skipping");
                            continue;
                        }
                        System.err.println("[DONE] seq=" + d.seq() + " firstDelta=" + firstDelta);
                        if (!firstDelta) {
                            MordantTerminal.flush(t);
                            MordantTerminal.println(t, "");
                        }
                        return d;
                    }

                    case IpcFrame.Error e -> {
                        // Skip stale errors from a previous request
                        if (e.seq() != 0 && e.seq() != seq) continue;
                        if (!firstDelta) {
                            MordantTerminal.flush(t);
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

    private class VetoCompleter implements Completer {

        @Override
        public void complete(LineReader r, ParsedLine line, List<Candidate> out) {
            String fullLine = line.line();
            if (!fullLine.startsWith("/")) return;
            // Pause the hint reader and drop any ghost so the completion output
            // neither races the socket nor collides with leftover ghost text.
            busy = true;
            long seq = nextSeq++;
            try {
                eraseGhostToEol();
                transport.send(new IpcFrame.Complete(fullLine, seq));
                IpcFrame reply = transport.receive();
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
            VetoTerminal vt = new VetoTerminal(mt, null, transport);
            Completer completer = vt.new VetoCompleter();
            LineReader r = LineReaderBuilder.builder().terminal(jt).completer(completer).build();
            vt.reader = r;
            vt.start();
        } catch (IOException e) {
            System.err.println("Terminal failed: " + e.getMessage());
        }
    }
}
