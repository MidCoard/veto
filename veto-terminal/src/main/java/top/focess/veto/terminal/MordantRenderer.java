package top.focess.veto.terminal;

import com.github.ajalt.mordant.terminal.Terminal;
import java.util.logging.Logger;
import org.jline.reader.LineReader;

/**
 * Unified rendering layer. All output goes through {@link LineReader#printAbove} to avoid
 * corrupting the input line during the REPL. Styled-text wrappers delegate to {@link
 * MordantTerminal} for ANSI formatting.
 */
public class MordantRenderer {

    private static final Logger log = Logger.getLogger(MordantRenderer.class.getName());

    private final Terminal terminal;
    private final LineReader reader;

    public MordantRenderer(Terminal terminal, LineReader reader) {
        this.terminal = terminal;
        this.reader = reader;
    }

    // ── styled text ───────────────────────────────────────────────────────

    public String dim(String text) {
        return MordantTerminal.dim(terminal, text);
    }

    public String bold(String text) {
        return MordantTerminal.bold(terminal, text);
    }

    public String cyan(String text) {
        return MordantTerminal.cyan(terminal, text);
    }

    public String red(String text) {
        return MordantTerminal.red(terminal, text);
    }

    public String green(String text) {
        return MordantTerminal.green(terminal, text);
    }

    public String yellow(String text) {
        return MordantTerminal.yellow(terminal, text);
    }

    // ── output ────────────────────────────────────────────────────────────

    public void println(String text) {
        reader.printAbove(text);
    }

    public void error(String text) {
        println(red("✗ " + text));
    }

    public void separator() {
        println(dim("─".repeat(50)));
    }
}
