package top.focess.veto.terminal;

import com.github.ajalt.mordant.terminal.Terminal;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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

    private TerminalStatus status;
    private volatile boolean running;

    private final StringBuilder deltaBuffer = new StringBuilder();
    private final AtomicReference<IpcFrame.Prompt> activePrompt = new AtomicReference<>(null);
    private Thread mainThread;

    public VetoTerminal(Terminal t, ZmqClient client) {
        this.t = t;
        this.client = client;
        this.renderer = new MordantRenderer(t);
    }

    public void start(LineReader reader) {
        this.reader = reader;
        this.renderer.setReader(reader);
        this.mainThread = Thread.currentThread();
        this.status = new TerminalStatus(reader.getTerminal(), t);
        this.status.refresh();

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
                                } catch (Exception e) {
                                    if (running) {
                                        log.log(Level.WARNING, "Error in heartbeat loop", e);
                                    }
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
            status.clear();
            client.send(new IpcFrame.Bye());
            client.close();
        }
    }

    // ── repl ──────────────────────────────────────────────────────────────

    private void repl() {
        while (running && !Thread.currentThread().isInterrupted()) {
            if (status != null) {
                status.refresh();
            }
            String line;
            try {
                line = reader.readLine(prompt());
            } catch (UserInterruptException e) {
                if (Thread.currentThread().isInterrupted()) {
                    Thread.interrupted(); // Clear interrupted status
                    if (!running) {
                        break;
                    }
                    continue;
                }
                client.send(new IpcFrame.Cancel());
                activePrompt.set(null);
                continue;
            } catch (EndOfFileException e) {
                break;
            }

            if (line == null) break;
            line = line.trim();

            IpcFrame.Prompt active = activePrompt.get();
            if (active != null) {
                client.send(new IpcFrame.Input(line));
                activePrompt.set(null);
                continue;
            }

            if (line.isEmpty()) continue;

            if (!line.startsWith("/")) {
                echoInput(line);
                reader.printAbove(MordantTerminal.dim(t, "  thinking…"));
            }

            executeRequest(line);
        }
        MordantTerminal.println(t, "  Goodbye.");
    }

    private void executeRequest(String line) {
        client.send(new IpcFrame.Request(line));
    }

    private void flushDeltaBuffer() {
        if (!deltaBuffer.isEmpty()) {
            reader.printAbove(deltaBuffer.toString());
            deltaBuffer.setLength(0);
        }
    }

    private void handleIncomingFrame(IpcFrame.ServerFrame frame) {
        if (frame instanceof IpcFrame.Delta d) {
            log.fine("[DELTA] len=" + d.content().length());
            deltaBuffer.append(d.content());
            int newlineIndex;
            while ((newlineIndex = deltaBuffer.indexOf("\n")) != -1) {
                String line = deltaBuffer.substring(0, newlineIndex);
                deltaBuffer.delete(0, newlineIndex + 1);
                reader.printAbove(line);
            }
        } else if (frame instanceof IpcFrame.Progress p) {
            flushDeltaBuffer();
            String styled = MordantTerminal.dim(t, "  ⏳ " + p.content());
            reader.printAbove(styled);
        } else if (frame instanceof IpcFrame.Prompt prompt) {
            flushDeltaBuffer();
            activePrompt.set(prompt);
            if (mainThread != null) {
                mainThread.interrupt();
            }
        } else if (frame instanceof IpcFrame.Done(Map<String, Object> meta, String content)) {
            flushDeltaBuffer();
            if (content != null && !content.isBlank()) {
                reader.printAbove(content);
            }
            applySessionMeta(meta);
            if (Boolean.TRUE.equals(meta.get(IpcMeta.EXIT))) {
                running = false;
            }
            if (mainThread != null) {
                mainThread.interrupt();
            }
        } else if (frame instanceof IpcFrame.Error e) {
            flushDeltaBuffer();
            renderer.error(e.content());
            if (mainThread != null) {
                mainThread.interrupt();
            }
        }
    }

    // ── session metadata ──────────────────────────────────────────────────

    private void applySessionMeta(Map<String, Object> meta) {
        if (status != null) {
            status.apply(meta);
        }
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

    private String prompt() {
        IpcFrame.Prompt active = activePrompt.get();
        if (active != null) {
            return "  " + active.content() + " ▸ ";
        }
        return (status != null && status.getDisplayUser() != null) ? "▸ " : "◇ ";
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
