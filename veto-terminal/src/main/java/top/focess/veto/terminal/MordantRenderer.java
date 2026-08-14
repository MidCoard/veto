package top.focess.veto.terminal;

import org.jline.reader.LineReader;
import org.jspecify.annotations.NonNull;
import top.focess.veto.client.core.Theme;

/**
 * Unified output seam for the REPL: every line of output goes through {@link LineReader#printAbove}
 * so it is rendered above the active input line without corrupting it.
 *
 * <p>Styled text is produced by a {@link Theme} (Mordant-backed, via {@link MordantTheme}) and
 * passed here as a ready-to-print string — this class holds no styling logic of its own.
 */
public final class MordantRenderer {

    private final @NonNull LineReader reader;

    public MordantRenderer(@NonNull LineReader reader) {
        this.reader = reader;
    }

    /**
     * Prints the specified (already-styled) text above the active prompt.
     *
     * @param text the text to print
     */
    public void println(@NonNull String text) {
        reader.printAbove(text);
    }
}
