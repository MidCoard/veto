package top.focess.veto.terminal;

import java.util.Map;

import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.Display;
import top.focess.veto.contract.ResponseType;
import top.focess.veto.contract.TerminalRequest;
import top.focess.veto.contract.TerminalResponse;

/**
 * Standalone terminal — the thin client. Communicates with the Veto backend exclusively through
 * file-based IPC. Performs zero command parsing, zero business logic.
 *
 * <p>Usage: {@code ./gradlew veto-terminal:run}
 */
public class VetoTerminal {

    private final LineReader reader;
    private final FileChannel channel;
    private final TerminalRenderer renderer;
    private final Spinner spinner;
    private final StatusLine statusLine;
    private String sessionToken;

    public VetoTerminal(Terminal terminal, Display display) {
        this.reader = LineReaderBuilder.builder().terminal(terminal).build();
        this.channel = new FileChannel();
        this.renderer = new TerminalRenderer(terminal);
        this.spinner = new Spinner(terminal);
        this.statusLine = new StatusLine(display);
    }

    public void start() {
        printBanner();

        // Health check
        if (!ChannelHealth.check(channel)) {
            renderer.error("Backend not reachable via " + channel.vaultHome() + "/terminal/");
            renderer.error("Start it with: ./gradlew veto-core:bootRun");
            return;
        }

        statusLine.show("Connected | /help for commands | /exit to quit");

        try {
            while (true) {
                String line = reader.readLine(prompt());
                if (line == null) break; // EOF (Ctrl+D)
                line = line.trim();
                if (line.isEmpty()) continue;

                // Local exit check
                if (line.equals("/exit") || line.equals("/quit") || line.equals("exit")) {
                    println("Goodbye.");
                    break;
                }

                // Send to backend
                spinner.start("Veto is thinking...");
                TerminalResponse resp =
                        channel.send(new TerminalRequest(line, sessionToken), 30_000);
                spinner.stop();

                if (resp == null) {
                    renderer.error("No response from backend — is it still running?");
                    statusLine.show("Disconnected");
                    continue;
                }

                // Handle session lifecycle from response metadata
                Map<String, Object> meta = resp.meta();
                if (meta.containsKey("username")) {
                    sessionToken = "session-" + meta.get("username");
                }
                if (Boolean.TRUE.equals(meta.get("clearSession"))) {
                    sessionToken = null;
                }
                if (Boolean.TRUE.equals(meta.get("exit"))) {
                    renderer.render(resp);
                    break;
                }

                renderer.render(resp);

                if (resp.type() == ResponseType.ERROR) {
                    statusLine.show("Last command failed");
                }
            }
        } finally {
            statusLine.hide();
            println("Goodbye.");
        }
    }

    private String prompt() {
        return sessionToken != null ? "veto> " : "veto(guest)> ";
    }

    private void printBanner() {
        String banner =
                "\033[36m+==================================+\n"
                        + "|  Veto Terminal  v2.0             |\n"
                        + "+==================================+\033[0m\n";
        println(banner);
    }

    private void println(String text) {
        System.out.println(text);
    }

    public static void main(String[] args) {
        System.setProperty("file.encoding", "UTF-8");
        try {
            Terminal terminal = TerminalBuilder.builder().system(true).encoding("UTF-8").build();
            Display display = new Display(terminal, true);
            new VetoTerminal(terminal, display).start();
        } catch (Exception e) {
            System.err.println("Failed to initialize terminal: " + e.getMessage());
            System.exit(1);
        }
    }
}
