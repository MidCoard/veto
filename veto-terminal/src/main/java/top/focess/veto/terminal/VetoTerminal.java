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
    private String sessionToken;
    private String displayUser;
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

            boolean isCommand = line.startsWith("/");

            if (!isCommand) {
                echoInput(line);
                MordantTerminal.println(t, MordantTerminal.dim(t, "  thinking..."));
            }

            FileChannel.SendResult sr =
                    channel.send(new TerminalRequest(line, sessionToken), 60_000);
            TerminalResponse resp = sr.response();

            if (resp == null) {
                renderer.error("No response — is backend running?");
                continue;
            }

            while (resp.type() == ResponseType.PROMPT) {
                boolean mask = Boolean.TRUE.equals(resp.meta().get("mask"));
                String promptText = "  " + resp.content() + " ";
                String reply =
                        mask ? reader.readLine(promptText, '*') : reader.readLine(promptText);
                if (reply == null || reply.trim().isEmpty()) break;
                resp =
                        channel.sendFollowUp(
                                sr.requestId(),
                                new TerminalRequest(reply.trim(), sessionToken),
                                60_000);
                if (resp == null) break;
            }
            if (resp == null) continue;

            Map<String, Object> meta = resp.meta();
            if (meta.containsKey("session")) sessionToken = (String) meta.get("session");
            if (meta.containsKey("username")) displayUser = (String) meta.get("username");
            if (Boolean.TRUE.equals(meta.get("clearSession"))) {
                sessionToken = null;
                displayUser = null;
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
        MordantTerminal.println(t, MordantTerminal.dim(t, "  terminal v2.0"));
        MordantTerminal.println(t, "");
    }

    // ── Tab completion ───────────────────────────────────────────────────────

    /**
     * Two-tier completer:
     *
     * <ol>
     *   <li><b>Command-name completion</b> (first word, no spaces): uses a fast in-memory cache.
     *   <li><b>Argument / sub-command completion</b> (has spaces): queries the backend via the file
     *       channel with a short timeout. The backend delegates to {@code
     *       CommandManager.complete()} which uses each command's registered argument completers.
     * </ol>
     */
    private static class VetoCompleter implements Completer {
        private final List<String> cache;
        private final FileChannel channel;

        VetoCompleter(List<String> cache, FileChannel channel) {
            this.cache = cache;
            this.channel = channel;
        }

        @Override
        public void complete(LineReader r, ParsedLine line, List<Candidate> out) {
            String fullLine = line.line();
            if (!fullLine.startsWith("/")) return;

            String afterSlash = fullLine.substring(1); // e.g. "log", "pattern cr"

            // Tier 1: command-name completion (no spaces) — use in-memory cache, instant
            if (!afterSlash.contains(" ")) {
                String prefix = afterSlash.toLowerCase();
                for (String cmd : cache) {
                    if (cmd.toLowerCase().startsWith(prefix) && !cmd.contains(" ")) {
                        out.add(new Candidate("/" + cmd));
                    }
                }
                return;
            }

            // Tier 2: argument / sub-command completion — ask backend via file channel
            List<String> fromBackend = channel.complete(fullLine, null /* sessionToken */, 2_000);
            for (String c : fromBackend) {
                String trimmed = c.trim();
                if (!trimmed.isEmpty()) {
                    out.add(new Candidate(trimmed));
                }
            }

            // Fallback: check cache for multi-word commands when backend is unreachable
            if (out.isEmpty()) {
                String wordAtCursor = line.word().toLowerCase();
                String prefix = afterSlash.toLowerCase();
                // The part before the word being completed, e.g. "pattern " from "pattern cr"
                int lastSpace = afterSlash.lastIndexOf(' ');
                String beforeWord = lastSpace >= 0 ? afterSlash.substring(0, lastSpace + 1) : "";
                for (String cmd : cache) {
                    String lowerCmd = cmd.toLowerCase();
                    if (lowerCmd.startsWith(prefix)) {
                        // Offer only the suffix that replaces the word at cursor
                        String completionPart = lowerCmd.substring(beforeWord.length());
                        if (completionPart.startsWith(wordAtCursor)) {
                            out.add(new Candidate(completionPart));
                        }
                    }
                }
            }
        }
    }

    /**
     * Fetch the initial command-name cache from the backend's /help output.
     */
    private static List<String> fetchCommands() {
        try {
            FileChannel ch = new FileChannel();
            var sr = ch.send(new TerminalRequest("/help"), 5_000);
            if (sr != null && sr.response() != null) return parse(sr.response().content());
        } catch (Exception ignored) {
        }
        return BUILTIN;
    }

    /**
     * Parse the /help output into a command list. Preserves sub-commands by extracting everything
     * between the leading {@code /} and the description column (two or more spaces).
     */
    private static List<String> parse(String help) {
        return help.lines()
                .map(String::trim)
                .filter(l -> l.startsWith("/"))
                .map(
                        l -> {
                            // "  /pattern create|list|use|delete|show  Manage agent patterns"
                            //  → extract "pattern create|list|use|delete|show"
                            String withoutSlash = l.substring(1); // drop leading "/"
                            // Split on 2+ spaces to separate command from description
                            String[] parts = withoutSlash.split("\\s{2,}", 2);
                            return parts[0].trim();
                        })
                .flatMap(
                        cmd -> {
                            // Expand "pattern create|list|use|delete|show" into individual entries
                            String[] words = cmd.split("\\s+", 2);
                            if (words.length < 2) return java.util.stream.Stream.of(cmd);
                            String base = words[0];
                            String subs = words[1]; // "create|list|use|delete|show"
                            return java.util.stream.Stream.concat(
                                    java.util.stream.Stream.of(base),
                                    java.util.stream.Stream.of(subs.split("\\|"))
                                            .map(s -> base + " " + s.trim()));
                        })
                .distinct()
                .toList();
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
            FileChannel compChannel = new FileChannel();
            LineReader r =
                    LineReaderBuilder.builder()
                            .terminal(jt)
                            .completer(new VetoCompleter(cmds, compChannel))
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
        String displayUser = null;
        String sessionToken = null;
        while (true) {
            System.out.print(displayUser != null ? "> " : "guest> ");
            System.out.flush();
            if (!sc.hasNextLine()) break;
            String line = sc.nextLine().trim();
            if (line.isEmpty()) continue;
            if (line.equals("/exit")) break;
            var sr = ch.send(new TerminalRequest(line, sessionToken), 60_000);
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
                                new TerminalRequest(sc.nextLine().trim(), sessionToken),
                                60_000);
            }
            Map<String, Object> meta = resp.meta();
            if (meta.containsKey("session")) sessionToken = (String) meta.get("session");
            if (meta.containsKey("username")) displayUser = (String) meta.get("username");
            if (Boolean.TRUE.equals(meta.get("clearSession"))) {
                sessionToken = null;
                displayUser = null;
            }
            if (Boolean.TRUE.equals(meta.get("exit"))) break;
            System.out.println(resp.content());
            System.out.println();
        }
        System.out.println("Goodbye.");
    }
}
