package top.focess.veto.terminal;

import java.util.List;
import java.util.Map;

import org.jline.terminal.Terminal;
import top.focess.veto.contract.TerminalResponse;

/**
 * Maps structured {@link TerminalResponse} objects to styled terminal output using JLine. The
 * backend never generates ANSI escape codes — all rendering decisions live here.
 */
public class TerminalRenderer {

    private static final String CYAN = "\033[36m";
    private static final String RED = "\033[31m";
    private static final String DIM = "\033[2m";
    private static final String BOLD = "\033[1m";
    private static final String RESET = "\033[0m";

    private final Terminal terminal;

    public TerminalRenderer(Terminal terminal) {
        this.terminal = terminal;
    }

    /**
     * Dispatch rendering by response type.
     */
    public void render(TerminalResponse resp) {
        switch (resp.type()) {
            case MESSAGE -> message(resp.content());
            case CODE -> codeBlock(resp.content(), resp.meta());
            case TABLE -> table(resp.meta());
            case ERROR -> error(resp.content());
            case LIST -> bulletList(resp.content());
            case SUGGESTION -> suggestion(resp.content());
            case PROMPT -> prompt(resp.content());
            case PROGRESS -> {
                /* handled by Spinner / StatusLine */
            }
        }
    }

    private void message(String text) {
        println(text);
    }

    @SuppressWarnings("unchecked")
    private void codeBlock(String code, Map<String, Object> meta) {
        String language = meta.getOrDefault("language", "").toString();
        String label = language.isEmpty() ? "code" : language;
        int width = Math.max(label.length() + 4, 40);

        println(CYAN + "┌── " + label + " " + "─".repeat(width - label.length() - 4) + RESET);
        println(code);
        println(CYAN + "└" + "─".repeat(width) + RESET);
    }

    public void error(String text) {
        println(RED + "✗ " + text + RESET);
    }

    private void suggestion(String text) {
        println(DIM + text + RESET);
    }

    private void bulletList(String text) {
        for (String line : text.split("\n")) {
            println("  • " + line);
        }
    }

    private void prompt(String text) {
        terminal.writer().print(BOLD + text + " " + RESET);
        terminal.writer().flush();
    }

    @SuppressWarnings("unchecked")
    private void table(Map<String, Object> meta) {
        List<String> headers = (List<String>) meta.get("headers");
        List<List<String>> rows = (List<List<String>>) meta.get("rows");
        if (headers == null || rows == null) {
            return;
        }

        // Compute column widths
        int cols = headers.size();
        int[] widths = new int[cols];
        for (int c = 0; c < cols; c++) {
            widths[c] = headers.get(c).length();
        }
        for (var row : rows) {
            for (int c = 0; c < Math.min(cols, row.size()); c++) {
                widths[c] = Math.max(widths[c], row.get(c).length());
            }
        }

        // Print header
        StringBuilder headerLine = new StringBuilder();
        for (int c = 0; c < cols; c++) {
            headerLine.append(String.format(" %-" + widths[c] + "s ", headers.get(c)));
        }
        println(BOLD + headerLine.toString().trim() + RESET);

        // Print separator
        StringBuilder sep = new StringBuilder();
        for (int c = 0; c < cols; c++) {
            sep.append("-".repeat(widths[c] + 2));
            if (c < cols - 1) sep.append("+");
        }
        println(DIM + sep.toString() + RESET);

        // Print rows
        for (var row : rows) {
            StringBuilder rowLine = new StringBuilder();
            for (int c = 0; c < cols; c++) {
                String val = c < row.size() ? row.get(c) : "";
                rowLine.append(String.format(" %-" + widths[c] + "s ", val));
            }
            println(rowLine.toString().trim());
        }
    }

    private void println(String text) {
        terminal.writer().println(text);
        terminal.writer().flush();
    }

    /**
     * Print a horizontal separator line.
     */
    public void separator() {
        terminal.writer().println(DIM + "-".repeat(50) + RESET);
        terminal.writer().flush();
    }
}
