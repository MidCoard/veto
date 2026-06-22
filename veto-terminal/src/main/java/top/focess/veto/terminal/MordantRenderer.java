package top.focess.veto.terminal;

import org.jetbrains.annotations.NotNull;
import org.jline.reader.LineReader;

/**
 * Unified output seam for the REPL: every line of output goes through {@link LineReader#printAbove}
 * so it is rendered above the active input line without corrupting it.
 *
 * <p>Styled text is produced by a {@link top.focess.veto.client.core.Theme} (Mordant-backed, via
 * {@link MordantTheme}) and passed here as a ready-to-print string — this class holds no styling
 * logic of its own.
 */
public final class MordantRenderer {

    private final LineReader reader;

    public MordantRenderer(@NotNull LineReader reader) {
        this.reader = reader;
    }

    /**
     * Prints the specified (already-styled) text above the active prompt.
     *
     * @param text the text to print
     */
    public void println(@NotNull String text) {
        reader.printAbove(text);
    }
}
