package top.focess.veto.terminal;

import com.github.ajalt.mordant.terminal.Terminal;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.jline.reader.*;
import org.jline.terminal.TerminalBuilder;
import top.focess.veto.contract.IpcFrame;

public class VetoTerminal {

    private static final String HEADER =
            """
                    ██╗   ██╗███████╗████████╗ ██████╗
                    ██║   ██║██╔════╝╚══██╔══╝██╔═══██╗
                    ██║   ██║█████╗     ██║   ██║   ██║
                    ╚██╗ ██╔╝██╔══╝     ██║   ██║   ██║
                     ╚████╔╝ ███████╗   ██║   ╚██████╔╝
                      ╚═══╝  ╚══════╝   ╚═╝    ╚═════╝
                    """;

    private final Terminal t;
    private LineReader reader;
    final FileChannel channel;
    private final MordantRenderer renderer;
    private final Object lock = new Object();

    private String sessionToken;
    private String displayUser;
    private int turnCount;
    private volatile boolean cancelRequested;

    public VetoTerminal(Terminal t, LineReader reader) {
        this.t = t;
        this.reader = reader;
        this.channel = new FileChannel();
        this.renderer = new MordantRenderer(t);
    }

    public void start() {
        printBanner();
        IpcFrame health = ChannelHealth.check(channel);
        if (!ChannelHealth.isReachable(health)) {
            renderer.error("Backend not reachable");
            renderer.error("Please start the Veto backend first.");
            return;
        }
        if (ChannelHealth.isFatal(health)) {
            renderer.error(((IpcFrame.Error) health).content());
            return;
        }
        // Keep the connection alive — backend drops terminals that go silent
        Thread heartbeat = channel.startHeartbeat(60_000);
        try {
            repl();
        } finally {
            heartbeat.interrupt();
            channel.bye();
        }
    }

    private void repl() {
        while (true) {
            printHint();
            String line = reader.readLine(prompt());
            if (line == null) break;
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.equals("/exit") || line.equals("/quit")) {
                MordantTerminal.println(t, "  Goodbye.");
                break;
            }

            boolean isCommand = line.startsWith("/");

            if (!isCommand) {
                echoInput(line);
                MordantTerminal.println(t, MordantTerminal.dim(t, "  thinking…"));
            }

            cancelRequested = false;
            IpcFrame result = handleExchange(new IpcFrame.Request(line));

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

    /**
     * Execute a single request→response exchange with streaming. Handles deltas, progress, prompts,
     * and cancellation.
     */
    private IpcFrame handleExchange(IpcFrame.Request request) {
        StringBuilder contentBuf = new StringBuilder();
        boolean[] firstDelta = {true};

        try {
            return channel.sendAndReceive(
                    request,
                    120_000,
                    new FileChannel.FrameHandler() {
                        @Override
                        public void onFrame(IpcFrame frame) {
                            switch (frame) {
                                case IpcFrame.Delta d -> {
                                    if (firstDelta[0]) {
                                        // Clear the "thinking..." line
                                        MordantTerminal.println(t, "");
                                        firstDelta[0] = false;
                                    }
                                    contentBuf.append(d.content());
                                    MordantTerminal.print(t, d.content());
                                }
                                case IpcFrame.Progress p -> {
                                    MordantTerminal.println(
                                            t, MordantTerminal.dim(t, "  ⏳ " + p.content()));
                                }
                                case IpcFrame.Done d -> {
                                    if (!firstDelta[0]) {
                                        MordantTerminal.println(t, "");
                                    }
                                }
                                case IpcFrame.Error e -> {
                                    if (!firstDelta[0]) {
                                        MordantTerminal.println(t, "");
                                    }
                                }
                                default -> {}
                            }
                        }

                        @Override
                        public IpcFrame.Input onPrompt(IpcFrame.Prompt prompt) {
                            MordantTerminal.println(t, "");
                            boolean mask = Boolean.TRUE.equals(prompt.meta().get("mask"));
                            // Pass prompt to JLine so it knows where the prompt ends,
                            // preventing backspace from erasing the prompt text.
                            String promptText = "  " + prompt.content() + " ";
                            String reply;
                            try {
                                reply =
                                        mask
                                                ? reader.readLine(promptText, '*')
                                                : reader.readLine(promptText);
                            } catch (Exception e) {
                                return null;
                            }
                            if (reply == null || reply.trim().isEmpty()) return null;
                            return new IpcFrame.Input(reply.trim());
                        }
                    });
        } catch (IOException e) {
            return new IpcFrame.Error("IPC error: " + e.getMessage());
        }
    }

    private void applySessionMeta(Map<String, Object> meta) {
        if (meta.containsKey("session")) sessionToken = (String) meta.get("session");
        if (meta.containsKey("username")) displayUser = (String) meta.get("username");
        if (Boolean.TRUE.equals(meta.get("clearSession"))) {
            sessionToken = null;
            displayUser = null;
            turnCount = 0;
        }
        if (meta.containsKey("turnNumber"))
            turnCount = ((Number) meta.get("turnNumber")).intValue();
    }

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

    // ── Tab completion ──────────────────────────────────────────────────

    /** Completer that delegates everything to the backend via a {@code complete} frame. */
    private static class VetoCompleter implements Completer {
        private final FileChannel channel;

        VetoCompleter(FileChannel channel) {
            this.channel = channel;
        }

        @Override
        public void complete(LineReader r, ParsedLine line, List<Candidate> out) {
            String fullLine = line.line();
            if (!fullLine.startsWith("/")) return;
            out.addAll(channel.complete(fullLine, 2_000));
        }
    }

    // ── main ────────────────────────────────────────────────────────────

    public static void main(String[] args) {
        System.setProperty("file.encoding", "UTF-8");
        java.util.logging.Logger.getLogger("org.jline").setLevel(java.util.logging.Level.OFF);
        try {
            org.jline.terminal.Terminal jt =
                    TerminalBuilder.builder().system(true).jna(true).encoding("UTF-8").build();
            Terminal mt = MordantTerminal.create();
            VetoTerminal vt = new VetoTerminal(mt, null);
            VetoCompleter completer = new VetoCompleter(vt.channel);
            LineReader r = LineReaderBuilder.builder().terminal(jt).completer(completer).build();
            vt.reader = r;
            vt.start();
        } catch (IOException e) {
            System.err.println("Terminal failed: " + e.getMessage());
            fallback();
        }
    }

    private static void fallbackExchange(FileChannel ch, String line) {
        StringBuilder buf = new StringBuilder();
        try {
            IpcFrame result =
                    ch.sendAndReceive(
                            new IpcFrame.Request(line),
                            120_000,
                            new FileChannel.FrameHandler() {
                                @Override
                                public void onFrame(IpcFrame frame) {
                                    if (frame instanceof IpcFrame.Delta d) {
                                        buf.append(d.content());
                                        System.out.print(d.content());
                                    } else if (frame instanceof IpcFrame.Progress p) {
                                        System.out.print("  ⏳ " + p.content());
                                    } else if (frame instanceof IpcFrame.Error e) {
                                        System.out.println();
                                    }
                                }

                                @Override
                                public IpcFrame.Input onPrompt(IpcFrame.Prompt prompt) {
                                    if (Boolean.TRUE.equals(prompt.meta().get("mask"))) {
                                        return new IpcFrame.Input(
                                                new String(
                                                        System.console()
                                                                .readPassword(
                                                                        "  "
                                                                                + prompt.content()
                                                                                + " ")));
                                    }
                                    System.out.print("  " + prompt.content() + " ");
                                    System.out.flush();
                                    String reply = new java.util.Scanner(System.in).nextLine();
                                    return new IpcFrame.Input(reply.trim());
                                }
                            });
            if (result instanceof IpcFrame.Done done
                    && Boolean.TRUE.equals(done.meta().get("exit"))) {
                System.out.println("Goodbye.");
                System.exit(0);
            }
            if (result instanceof IpcFrame.Error err) {
                System.out.println("[!] " + err.content());
            }
        } catch (IOException e) {
            System.out.println("[!] IPC error: " + e.getMessage());
        }
    }

    private static void fallback() {
        System.out.println("Veto Terminal v3.0");
        FileChannel ch = new FileChannel();
        IpcFrame health = ChannelHealth.check(ch);
        if (!ChannelHealth.isReachable(health)) {
            System.out.println("Backend not reachable.");
            return;
        }
        if (ChannelHealth.isFatal(health)) {
            System.out.println("[!] " + ((IpcFrame.Error) health).content());
            return;
        }
        Thread heartbeat = ch.startHeartbeat(60_000);
        try {
            java.util.Scanner sc = new java.util.Scanner(System.in);
            String displayUser = null;
            while (true) {
                System.out.print(displayUser != null ? "> " : "guest> ");
                System.out.flush();
                if (!sc.hasNextLine()) break;
                String line = sc.nextLine().trim();
                if (line.isEmpty()) continue;
                if (line.equals("/exit")) break;

                System.out.println("  thinking…");
                fallbackExchange(ch, line);
                System.out.println();
            }
        } finally {
            heartbeat.interrupt();
            ch.bye();
        }
        System.out.println("Goodbye.");
    }
}
