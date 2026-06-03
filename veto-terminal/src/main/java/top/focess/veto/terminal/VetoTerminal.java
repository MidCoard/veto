package top.focess.veto.terminal;

import com.github.ajalt.mordant.terminal.Terminal;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.jline.reader.*;
import org.jline.terminal.TerminalBuilder;
import top.focess.veto.contract.ResponseType;
import top.focess.veto.contract.TerminalRequest;
import top.focess.veto.contract.TerminalResponse;

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

    private static final List<String> BUILTIN =
            List.of(
                    "login",
                    "logout",
                    "signup",
                    "status",
                    "exit",
                    "help",
                    "pattern",
                    "pattern create",
                    "pattern list",
                    "pattern use",
                    "pattern delete",
                    "pattern show");

    private final Terminal t;
    private final LineReader reader;
    private final FileChannel channel;
    private final MordantRenderer renderer;
    private String sessionUser;
    private int turnCount;

    public VetoTerminal(Terminal t, LineReader reader) {
        this.t = t;
        this.reader = reader;
        this.channel = new FileChannel();
        this.renderer = new MordantRenderer(t);
    }

    public void start() {
        printBanner();
        if (!ChannelHealth.check(channel)) {
            renderer.error("Backend not reachable");
            renderer.error("Please start the Veto backend first.");
            return;
        }
        repl();
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

            String token = sessionUser != null ? "session-" + sessionUser : null;
            boolean isCommand = line.startsWith("/");

            if (!isCommand) {
                echoInput(line);
                MordantTerminal.println(t, MordantTerminal.dim(t, "  thinking..."));
            }

            FileChannel.SendResult sr = channel.send(new TerminalRequest(line, token), 60_000);
            TerminalResponse resp = sr.response();

            if (resp == null) {
                renderer.error("No response — is backend running?");
                continue;
            }

            while (resp.type() == ResponseType.PROMPT) {
                MordantTerminal.println(t, "  " + resp.content());
                boolean mask = Boolean.TRUE.equals(resp.meta().get("mask"));
                String reply = mask ? reader.readLine("  ", '*') : reader.readLine("  ");
                if (reply == null || reply.trim().isEmpty()) break;
                resp =
                        channel.sendFollowUp(
                                sr.requestId(), new TerminalRequest(reply.trim(), token), 60_000);
                if (resp == null) break;
            }
            if (resp == null) continue;

            Map<String, Object> meta = resp.meta();
            if (meta.containsKey("username")) sessionUser = (String) meta.get("username");
            if (Boolean.TRUE.equals(meta.get("clearSession"))) {
                sessionUser = null;
                turnCount = 0;
            }
            if (meta.containsKey("turnNumber"))
                turnCount = ((Number) meta.get("turnNumber")).intValue();
            if (Boolean.TRUE.equals(meta.get("exit"))) break;

            MordantTerminal.println(t, "");
            renderer.render(resp);
            MordantTerminal.println(t, "");
        }
        MordantTerminal.println(t, "  Goodbye.");
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
        if (sessionUser == null) {
            MordantTerminal.println(t, MordantTerminal.dim(t, "  /login to start | /help"));
        } else {
            MordantTerminal.println(
                    t, MordantTerminal.dim(t, "  " + sessionUser + " | turns: " + turnCount));
        }
    }

    private String prompt() {
        return sessionUser != null ? "▸ " : "◇ ";
    }

    private void printBanner() {
        for (String line : HEADER.split("\n"))
            MordantTerminal.println(t, MordantTerminal.bold(t, MordantTerminal.cyan(t, line)));
        MordantTerminal.println(t, MordantTerminal.dim(t, "  terminal v2.0"));
        MordantTerminal.println(t, "");
    }

    // ── Tab completion ───────────────────────────────────────────────────────

    private record VetoCompleter(List<String> cmds) implements Completer {
        @Override
        public void complete(LineReader r, ParsedLine line, List<Candidate> out) {
            String w = line.word().toLowerCase();
            String buf = line.line().toLowerCase();
            for (String c : cmds) {
                if (buf.startsWith("/") && w.isEmpty() && !c.contains(" ")) {
                    out.add(new Candidate("/" + c));
                } else if (("/" + c).startsWith(buf) || c.startsWith(w)) {
                    out.add(new Candidate(c));
                }
            }
        }
    }

    private static List<String> fetchCommands() {
        try {
            FileChannel ch = new FileChannel();
            var sr = ch.send(new TerminalRequest("/help"), 5_000);
            if (sr != null && sr.response() != null) return parse(sr.response().content());
        } catch (Exception ignored) {
        }
        return BUILTIN;
    }

    private static List<String> parse(String help) {
        return List.of(
                help.lines()
                        .map(String::trim)
                        .filter(l -> l.startsWith("/"))
                        .map(l -> l.substring(1).split(" ")[0])
                        .distinct()
                        .toArray(String[]::new));
    }

    // ── main ─────────────────────────────────────────────────────────────────

    public static void main(String[] args) {
        System.setProperty("file.encoding", "UTF-8");
        java.util.logging.Logger.getLogger("org.jline").setLevel(java.util.logging.Level.OFF);
        try {
            org.jline.terminal.Terminal jt =
                    TerminalBuilder.builder().system(true).jna(true).encoding("UTF-8").build();
            Terminal mt = MordantTerminal.create();
            List<String> cmds = fetchCommands();
            LineReader r =
                    LineReaderBuilder.builder()
                            .terminal(jt)
                            .completer(new VetoCompleter(cmds))
                            .build();
            new VetoTerminal(mt, r).start();
        } catch (IOException e) {
            System.err.println("Terminal failed: " + e.getMessage());
            fallback();
        }
    }

    private static void fallback() {
        System.out.println("Veto Terminal v2.0");
        FileChannel ch = new FileChannel();
        if (!ChannelHealth.check(ch)) {
            System.out.println("Backend not reachable.");
            return;
        }
        java.util.Scanner sc = new java.util.Scanner(System.in);
        String u = null;
        while (true) {
            System.out.print(u != null ? "> " : "guest> ");
            System.out.flush();
            if (!sc.hasNextLine()) break;
            String line = sc.nextLine().trim();
            if (line.isEmpty()) continue;
            if (line.equals("/exit")) break;
            var sr = ch.send(new TerminalRequest(line, u != null ? "session-" + u : null), 60_000);
            TerminalResponse resp = sr.response();
            if (resp == null) {
                System.out.println("[!] No response.");
                continue;
            }
            while (resp.type() == ResponseType.PROMPT) {
                System.out.print("  " + resp.content() + " ");
                System.out.flush();
                resp =
                        ch.sendFollowUp(
                                sr.requestId(),
                                new TerminalRequest(
                                        sc.nextLine().trim(), u != null ? "session-" + u : null),
                                60_000);
            }
            if (resp.meta().containsKey("username")) u = (String) resp.meta().get("username");
            if (Boolean.TRUE.equals(resp.meta().get("clearSession"))) u = null;
            if (Boolean.TRUE.equals(resp.meta().get("exit"))) break;
            System.out.println(resp.content());
            System.out.println();
        }
        System.out.println("Goodbye.");
    }
}
