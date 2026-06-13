package top.focess.veto.terminal;

import com.github.ajalt.mordant.terminal.Terminal;
import org.jline.reader.LineReader;

/**
 * Unified rendering layer. All output goes through {@link LineReader#printAbove} to avoid
 * corrupting the input line during the REPL. Styled-text wrappers delegate to {@link
 * MordantTerminal} for ANSI formatting.
 */
public class MordantRenderer {

    private final Terminal terminal;
    private final LineReader reader;

    /**
     * Constructs a new MordantRenderer instance.
     *
     * @param terminal the Mordant Terminal instance used for color/style formatting
     * @param reader the JLine LineReader instance used to output text above the active command
     *     prompt
     */
    public MordantRenderer(Terminal terminal, LineReader reader) {
        this.terminal = terminal;
        this.reader = reader;
    }

    // ── styled text ───────────────────────────────────────────────────────

    /**
     * Applies dim (grayed out) styling to the specified text.
     *
     * @param text the plain text
     * @return the styled ANSI string
     */
    public String dim(String text) {
        return MordantTerminal.dim(terminal, text);
    }

    /**
     * Applies bold styling to the specified text.
     *
     * @param text the plain text
     * @return the styled ANSI string
     */
    public String bold(String text) {
        return MordantTerminal.bold(terminal, text);
    }

    /**
     * Applies cyan styling to the specified text.
     *
     * @param text the plain text
     * @return the styled ANSI string
     */
    public String cyan(String text) {
        return MordantTerminal.cyan(terminal, text);
    }

    /**
     * Applies red styling to the specified text.
     *
     * @param text the plain text
     * @return the styled ANSI string
     */
    public String red(String text) {
        return MordantTerminal.red(terminal, text);
    }

    /**
     * Applies green styling to the specified text.
     *
     * @param text the plain text
     * @return the styled ANSI string
     */
    public String green(String text) {
        return MordantTerminal.green(terminal, text);
    }

    /**
     * Applies yellow styling to the specified text.
     *
     * @param text the plain text
     * @return the styled ANSI string
     */
    public String yellow(String text) {
        return MordantTerminal.yellow(terminal, text);
    }

    // ── output ────────────────────────────────────────────────────────────

    /**
     * Prints the specified text to the terminal above the active prompt, automatically ensuring a
     * trailing newline.
     *
     * @param text the text to print
     */
    public void println(String text) {
        // LineReader.printAbove prints the text and repositions/redraws the command line input
        // prompt.
        reader.printAbove(text);
    }

    /**
     * Prints the specified text chunk to the terminal above the active prompt without automatically
     * forcing a newline.
     *
     * @param text the text to print
     */
    public void print(String text) {
        // Since JLine's printAbove is line-oriented, it redraws the prompt immediately.
        reader.printAbove(text);
    }

    /**
     * Formats and prints an error message to the terminal.
     *
     * @param text the error message
     */
    public void error(String text) {
        println(red("Error: " + text));
    }

    /** Prints a stylized visual horizontal separator line to the terminal. */
    public void separator() {
        println(dim("─".repeat(50)));
    }
}
