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
    private final ZmqClient client;
    private LineReader reader;
    private MordantRenderer renderer;

    private TerminalStatus status;
    private volatile boolean running;
    private volatile boolean programmaticInterrupt = false;
    private volatile boolean waitingForResponse = false;

    private final StringBuilder deltaBuffer = new StringBuilder();
    private final AtomicReference<IpcFrame.Prompt> activePrompt = new AtomicReference<>(null);
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
        this.status.refresh();

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
                                    handleIncomingFrame(frame);
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
            status.clear();
            client.send(new IpcFrame.Bye());
            client.close();
        }
    }

    // ── repl ──────────────────────────────────────────────────────────────

    private void repl() {
        while (running) {
            status.refresh();
            String line;
            try {
                IpcFrame.Prompt active = activePrompt.get();
                Character mask = null;
                if (active != null && Boolean.TRUE.equals(active.meta().get("mask"))) {
                    mask = '*';
                }
                line = reader.readLine(prompt(), mask);
            } catch (UserInterruptException e) {
                if (programmaticInterrupt) {
                    programmaticInterrupt = false;
                    if (!running) {
                        break;
                    }
                    continue;
                }
                if (waitingForResponse || activePrompt.get() != null) {
                    client.send(new IpcFrame.Cancel());
                    activePrompt.set(null);
                    waitingForResponse = false;
                    continue;
                } else {
                    break;
                }
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
                renderer.println(renderer.dim("  thinking…"));
            }

            executeRequest(line);
        }
        renderer.println("  Goodbye.");
    }

    private void executeRequest(String line) {
        waitingForResponse = true;
        client.send(new IpcFrame.Request(line));
    }

    private void flushDeltaBuffer() {
        if (!deltaBuffer.isEmpty()) {
            renderer.println(deltaBuffer.toString());
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
                renderer.println(line);
            }
        } else if (frame instanceof IpcFrame.Progress p) {
            flushDeltaBuffer();
            String styled = renderer.dim("  ⏳ " + p.content());
            renderer.println(styled);
        } else if (frame instanceof IpcFrame.Prompt prompt) {
            flushDeltaBuffer();
            activePrompt.set(prompt);
            programmaticInterrupt = true;
            mainThread.interrupt();
        } else if (frame instanceof IpcFrame.Done(Map<String, Object> meta, String content)) {
            waitingForResponse = false;
            flushDeltaBuffer();
            if (content != null && !content.isBlank()) {
                renderer.println(content);
            }
            status.apply(meta);
            if (Boolean.TRUE.equals(meta.get(IpcMeta.EXIT))) {
                running = false;
            }
            programmaticInterrupt = true;
            mainThread.interrupt();
        } else if (frame instanceof IpcFrame.Error e) {
            waitingForResponse = false;
            flushDeltaBuffer();
            renderer.error(e.content());
            programmaticInterrupt = true;
            mainThread.interrupt();
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

    private String prompt() {
        IpcFrame.Prompt active = activePrompt.get();
        if (active != null) {
            return "  " + renderer.cyan(active.content()) + " " + renderer.yellow("▸") + " ";
        }
        return status.getDisplayUser() != null ? renderer.green("▸ ") : renderer.red("◇ ");
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
        Logger.getLogger("org.jline").setLevel(Level.OFF);
        Logger.getLogger("top.focess.veto.terminal").setLevel(Level.FINE);

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
